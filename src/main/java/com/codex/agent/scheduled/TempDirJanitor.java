package com.codex.agent.scheduled;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

/**
 * Backstop cleanup for orphaned exec-* directories.
 *
 * <p>{@code DockerExecutor.cleanup()} normally deletes its temp directory in
 * the {@code finally} block of {@code ExecutionRunner.run()}. This janitor
 * exists to mop up the rare cases where:
 * <ul>
 *   <li>The HTTP request was canceled mid-flight and the finally never ran</li>
 *   <li>The agent was killed (OOM, restart) while a submission was in progress</li>
 *   <li>A bug somewhere skipped cleanup</li>
 * </ul>
 *
 * <p>Runs every 30 minutes, removes any {@code exec-*} subdirectory of
 * {@code execution.temp-dir} older than {@code executor.agent.janitor.max-age-minutes}
 * (default 60 min).
 */
@Component
@Slf4j
public class TempDirJanitor {

    @Value("${execution.temp-dir:/tmp/codex}")
    private String tempBaseDir;

    @Value("${executor.agent.janitor.max-age-minutes:60}")
    private long maxAgeMinutes;

    /** Run every 30 minutes. */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void sweep() {
        Path baseDir = Paths.get(tempBaseDir);
        File baseFile = baseDir.toFile();
        if (!baseFile.exists() || !baseFile.isDirectory()) {
            return;
        }

        File[] entries = baseFile.listFiles();
        if (entries == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(maxAgeMinutes));
        int swept = 0;

        for (File entry : entries) {
            if (!entry.isDirectory()) continue;
            if (!entry.getName().startsWith("exec-")) continue;

            try {
                Instant lastModified = Files.getLastModifiedTime(entry.toPath()).toInstant();
                if (lastModified.isBefore(cutoff)) {
                    deleteRecursive(entry);
                    swept++;
                    log.warn("Janitor swept orphaned temp dir: {} (age {} min)",
                            entry.getName(),
                            Duration.between(lastModified, Instant.now()).toMinutes());
                }
            } catch (Exception e) {
                log.error("Janitor error inspecting {}: {}", entry.getName(), e.getMessage());
            }
        }

        if (swept > 0) {
            log.info("Janitor swept {} orphaned temp dir(s)", swept);
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        if (!f.delete()) {
            log.warn("Janitor failed to delete: {}", f.getAbsolutePath());
        }
    }
}
