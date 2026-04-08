package com.codex.agent.service;

import com.codex.agent.dto.ExecuteRequest;
import com.codex.agent.dto.ExecuteResponse;
import com.codex.agent.execution.DockerExecutor;
import com.codex.agent.execution.ExecutionResult;
import com.codex.agent.execution.OutputNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless orchestration of a single submission execution.
 *
 * <p>This is the equivalent of {@code ExecutionService.executeSubmission()}
 * on the backend side, but it does NOT touch the database, the queue, the
 * SSE service, or any user-facing concept. It just takes a request, drives
 * {@link DockerExecutor}, and returns a result. The Render-side backend is
 * responsible for everything else (persistence, SSE, rate limiting, auth).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionRunner {

    private final DockerExecutor dockerExecutor;

    public ExecuteResponse run(ExecuteRequest request) {
        String containerId = null;
        Path tempDir = null;

        long compileTimeMs = 0;
        long totalExecTimeMs = 0;
        int passedCount = 0;
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();
        List<ExecuteResponse.TestResult> testResults = new ArrayList<>();

        String finalStatus;
        String compileOutput = null;

        try {
            // 1. Prepare workspace (write source file to host temp dir, will be bind-mounted)
            tempDir = dockerExecutor.prepareTempDirectory(
                    request.getSourceCode(),
                    "solution" + request.getFileExtension());

            // 2. Create and start ONE container
            containerId = dockerExecutor.createAndStartContainer(
                    request.getDockerImage(),
                    tempDir,
                    request.getMemoryLimitMb());

            // 3. Compile (or skip for interpreted languages)
            ExecutionResult compileError = dockerExecutor.compileInContainer(
                    containerId,
                    request.getCompileCommand(),
                    request.getCompileTimeoutMs());

            if (compileError != null) {
                // Compilation failed
                finalStatus = "COMPILATION_ERROR";
                compileTimeMs = compileError.getExecutionTimeMs() != null ? compileError.getExecutionTimeMs() : 0;
                compileOutput = compileError.getStderr();
                stdoutBuilder.append(compileError.getStdout());
                stderrBuilder.append(compileError.getStderr());

                // Mark every test case as SKIPPED so the response shape stays consistent
                for (ExecuteRequest.TestCase tc : request.getTestCases()) {
                    testResults.add(ExecuteResponse.TestResult.builder()
                            .testCaseId(tc.getId())
                            .status("SKIPPED")
                            .stdout("")
                            .stderr("")
                            .execTimeMs(0)
                            .exitCode(-1)
                            .build());
                }

                log.warn("Compilation failed for submission {} ({}ms)", request.getSubmissionId(), compileTimeMs);
            } else {
                // 4. Run each test case in the SAME container
                finalStatus = "ACCEPTED";

                int testIndex = 0;
                for (ExecuteRequest.TestCase testCase : request.getTestCases()) {
                    testIndex++;
                    log.info("Submission {}: running test {}/{} (id={})",
                            request.getSubmissionId(), testIndex, request.getTestCases().size(), testCase.getId());

                    // If a previous test case failed, mark this one as SKIPPED and continue
                    if (!"ACCEPTED".equals(finalStatus)) {
                        testResults.add(ExecuteResponse.TestResult.builder()
                                .testCaseId(testCase.getId())
                                .status("SKIPPED")
                                .stdout("")
                                .stderr("")
                                .execTimeMs(0)
                                .exitCode(-1)
                                .build());
                        continue;
                    }

                    ExecutionResult result;
                    try {
                        result = dockerExecutor.runTestCase(
                                containerId,
                                request.getExecuteCommand(),
                                testCase.getStdin(),
                                request.getRunTimeoutMs(),
                                tempDir);
                    } catch (Exception e) {
                        log.error("Submission {}: test {} threw", request.getSubmissionId(), testCase.getId(), e);
                        finalStatus = "RUNTIME_ERROR";
                        testResults.add(ExecuteResponse.TestResult.builder()
                                .testCaseId(testCase.getId())
                                .status("RUNTIME_ERROR")
                                .stdout("")
                                .stderr(e.getMessage() == null ? "execution failed" : e.getMessage())
                                .execTimeMs(0)
                                .exitCode(-1)
                                .build());
                        continue;
                    }

                    long execTime = result.getExecutionTimeMs() != null ? result.getExecutionTimeMs() : 0;
                    int exitCode = result.getExitCode() != null ? result.getExitCode() : -1;
                    totalExecTimeMs += execTime;
                    stdoutBuilder.append(result.getStdout()).append("\n");
                    stderrBuilder.append(result.getStderr()).append("\n");

                    String testStatus;

                    if (exitCode != 0) {
                        // Non-zero exit. Distinguish OOM kill, TLE, and generic runtime error.
                        if (exitCode == 137) {
                            testStatus = "MEMORY_LIMIT_EXCEEDED";
                            finalStatus = "MEMORY_LIMIT_EXCEEDED";
                        } else if (exitCode == -1 && result.getStderr() != null
                                && result.getStderr().contains("timed out")) {
                            // executeCommandInContainer marks timeouts with exitCode=-1 + stderr "Execution timed out"
                            testStatus = "TIME_LIMIT_EXCEEDED";
                            finalStatus = "TIME_LIMIT_EXCEEDED";
                        } else {
                            testStatus = "RUNTIME_ERROR";
                            finalStatus = "RUNTIME_ERROR";
                        }
                    } else if (execTime > request.getRunTimeoutMs()) {
                        // Wall-clock TLE belt-and-braces (executeCommandInContainer should have caught this)
                        testStatus = "TIME_LIMIT_EXCEEDED";
                        finalStatus = "TIME_LIMIT_EXCEEDED";
                    } else if (OutputNormalizer.areEqual(testCase.getExpectedStdout(), result.getStdout())) {
                        testStatus = "PASSED";
                        passedCount++;
                    } else {
                        testStatus = "FAILED";
                        finalStatus = "WRONG_ANSWER";
                    }

                    testResults.add(ExecuteResponse.TestResult.builder()
                            .testCaseId(testCase.getId())
                            .status(testStatus)
                            .stdout(result.getStdout())
                            .stderr(result.getStderr())
                            .execTimeMs(execTime)
                            .exitCode(exitCode)
                            .build());

                    log.info("Submission {}: test {} -> {} ({}ms)",
                            request.getSubmissionId(), testCase.getId(), testStatus, execTime);
                }
            }

        } catch (Exception e) {
            log.error("Submission {}: unexpected error during execution", request.getSubmissionId(), e);
            finalStatus = "RUNTIME_ERROR";
            stderrBuilder.append(e.getMessage() == null ? "execution failed" : e.getMessage());
        } finally {
            // 5. Cleanup — always runs
            dockerExecutor.cleanup(containerId, tempDir);
        }

        log.info("╔══════════════════════════════════════════════════");
        log.info("║ Submission  : {}", request.getSubmissionId());
        log.info("║ Verdict     : {}", finalStatus);
        log.info("║ Test Cases  : {}/{} passed", passedCount, request.getTestCases().size());
        log.info("║ Compile     : {}ms", compileTimeMs);
        log.info("║ Run total   : {}ms (per-test limit: {}ms)", totalExecTimeMs, request.getRunTimeoutMs());
        log.info("║ Memory      : limit {}MB", request.getMemoryLimitMb());
        log.info("╚══════════════════════════════════════════════════");

        return ExecuteResponse.builder()
                .submissionId(request.getSubmissionId())
                .status(finalStatus)
                .compileOutput(compileOutput)
                .compileTimeMs(compileTimeMs)
                .totalExecTimeMs(totalExecTimeMs)
                .passedTestCases(passedCount)
                .totalTestCases(request.getTestCases().size())
                .stdout(stdoutBuilder.toString())
                .stderr(stderrBuilder.toString())
                .results(testResults)
                .build();
    }
}
