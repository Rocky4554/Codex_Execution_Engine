package com.codex.agent.execution;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Verbatim port of the platform's {@code DockerExecutor} into the agent's
 * package namespace. Behavior is identical: write source to host temp dir,
 * spin up one container per submission, compile once, run all test cases
 * via {@code docker exec}, then clean up.
 *
 * <p>The agent owns the host filesystem path it writes to, so the bind
 * mount works because both the agent process and the Docker daemon are on
 * the same machine (EC2). This is the entire reason the agent exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DockerExecutor {

    private final DockerClient dockerClient;

    @Value("${execution.temp-dir:/tmp/codex}")
    private String tempBaseDir;

    @Value("${execution.default-time-limit-ms:5000}")
    private int defaultTimeLimit;

    @Value("${execution.default-memory-limit-mb:256}")
    private int defaultMemoryLimit;

    /** Create a temp directory and write the source code file. */
    public Path prepareTempDirectory(String sourceCode, String fileName) throws IOException {
        log.debug("Preparing temp directory for file: {}", fileName);
        Path tempDir = createTempDirectory();
        Path sourceFile = tempDir.resolve(fileName);
        Files.writeString(sourceFile, sourceCode);
        log.debug("Temp directory prepared: {}", tempDir);
        return tempDir;
    }

    /**
     * Ensure a Docker image exists locally; pull it if missing.
     * Times out after 5 minutes to avoid blocking the worker forever.
     */
    public void ensureImageExists(String dockerImage) {
        try {
            dockerClient.inspectImageCmd(dockerImage).exec();
            log.debug("Image already present locally: {}", dockerImage);
        } catch (NotFoundException e) {
            log.info("Image not found locally, pulling: {} (this may take a few minutes)...", dockerImage);
            try {
                dockerClient.pullImageCmd(dockerImage)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion(5, TimeUnit.MINUTES);
                log.info("Successfully pulled image: {}", dockerImage);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling image: " + dockerImage, ie);
            }
        }
    }

    /**
     * Create and start a container for the given image + workspace.
     * Wrapped in a 60-second hard timeout so a frozen Docker daemon cannot
     * block the worker thread indefinitely.
     */
    public String createAndStartContainer(String dockerImage, Path workDir, int memoryLimitMb) {
        log.debug("Creating container for image: {} with workDir: {}", dockerImage, workDir);

        ensureImageExists(dockerImage);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(new Bind(workDir.toString(), new Volume("/workspace")))
                    .withNetworkMode("none")
                    .withMemory((long) memoryLimitMb * 1024 * 1024)
                    .withMemorySwap((long) memoryLimitMb * 1024 * 1024)
                    .withCpuQuota(50000L)
                    .withPidsLimit(50L)
                    .withReadonlyRootfs(true)
                    .withSecurityOpts(List.of("no-new-privileges"))
                    .withAutoRemove(false);

            CreateContainerResponse container = dockerClient.createContainerCmd(dockerImage)
                    .withHostConfig(hostConfig)
                    .withWorkingDir("/workspace")
                    .withCmd("sleep", "120")
                    .exec();

            String containerId = container.getId();
            log.debug("Container created: {}. Starting...", containerId);

            dockerClient.startContainerCmd(containerId).exec();
            log.info("Created and started container: {}", containerId);
            return containerId;
        });

        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Docker container creation timed out after 60s — is the daemon responsive?", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create Docker container: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during container creation", e);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Compile source code inside the container.
     * @return null on success or no-compile-needed; an ExecutionResult describing the failure otherwise.
     */
    public ExecutionResult compileInContainer(String containerId, String compileCommand, int compileTimeoutMs) throws Exception {
        if (compileCommand == null || compileCommand.isEmpty()) {
            log.debug("No compile command provided, skipping compilation.");
            return null;
        }

        log.info("Compiling in container {} with command: {}", containerId, compileCommand);
        ExecutionResult compileResult = executeCommandInContainer(containerId, compileCommand, compileTimeoutMs);

        if (!compileResult.isSuccess() || compileResult.getExitCode() != 0) {
            log.warn("Compilation failed in container {}: {}", containerId, compileResult.getStderr());
            return new ExecutionResult(
                    compileResult.getStdout(),
                    compileResult.getStderr(),
                    compileResult.getExitCode(),
                    compileResult.getExecutionTimeMs(),
                    false);
        }

        log.info("Compilation succeeded in container {}", containerId);
        return null;
    }

    /**
     * Run a single test case inside the container.
     * Writes input to /workspace/input.txt on the host (which is bind-mounted)
     * and pipes it into the program via shell redirect.
     */
    public ExecutionResult runTestCase(String containerId, String executeCommand,
                                       String input, int timeLimitMs, Path workDir) throws Exception {

        Path inputFile = workDir.resolve("input.txt");
        Files.writeString(inputFile, input == null ? "" : input);

        String inputRedirect = (input != null && !input.isEmpty()) ? " < /workspace/input.txt" : "";
        String fullCommand = executeCommand + inputRedirect;

        long startTime = System.currentTimeMillis();
        ExecutionResult result = executeCommandInContainer(containerId, fullCommand, timeLimitMs);
        long executionTime = System.currentTimeMillis() - startTime;

        result.setExecutionTimeMs(executionTime);
        result.setSuccess(result.getExitCode() == 0);

        return result;
    }

    /** Execute a command inside a running container. */
    private ExecutionResult executeCommandInContainer(String containerId, String command, int timeLimitMs) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        long startTime = System.currentTimeMillis();

        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd("/bin/sh", "-c", command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        boolean completed;
        try {
            completed = dockerClient.execStartCmd(execId)
                    .exec(new ExecStartResultCallback(stdout, stderr))
                    .awaitCompletion(timeLimitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("Command execution interrupted", e);
            throw new RuntimeException("Execution interrupted", e);
        }

        long executionTime = System.currentTimeMillis() - startTime;

        if (!completed) {
            log.warn("Command timed out after {}ms for exec {}", timeLimitMs, execId);
            return new ExecutionResult(
                    stdout.toString(),
                    "Execution timed out after " + timeLimitMs + "ms",
                    -1,
                    executionTime,
                    false);
        }

        // Get exit code with retry for Docker delay
        int exitCode = -1;
        for (int i = 0; i < 5; i++) {
            Long exitCodeLong = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            if (exitCodeLong != null) {
                exitCode = exitCodeLong.intValue();
                break;
            }
            log.debug("Exit code not yet available, retrying... (attempt {})", i + 1);
            Thread.sleep(500);
        }

        if (exitCode == -1) {
            log.warn("Exit code still null after retries for exec {}", execId);
        }

        return new ExecutionResult(
                stdout.toString(),
                stderr.toString(),
                exitCode,
                executionTime,
                exitCode == 0);
    }

    /** Cleanup: stop + remove container and delete temp directory. */
    public void cleanup(String containerId, Path tempDir) {
        if (containerId != null) {
            try {
                dockerClient.removeContainerCmd(containerId)
                        .withForce(true)
                        .exec();
                log.info("Removed container: {}", containerId);
            } catch (Exception e) {
                log.error("Error removing container: {}", containerId, e);
            }
        }

        if (tempDir != null) {
            try {
                deleteDirectory(tempDir.toFile());
                log.info("Deleted temp directory: {}", tempDir);
            } catch (Exception e) {
                log.error("Error deleting temp directory: {}", tempDir, e);
            }
        }
    }

    private Path createTempDirectory() throws IOException {
        Path baseDir = Paths.get(tempBaseDir);
        Files.createDirectories(baseDir);
        return Files.createTempDirectory(baseDir, "exec-");
    }

    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }
}
