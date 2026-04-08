package com.codex.agent.controller;

import com.codex.agent.dto.ExecuteRequest;
import com.codex.agent.dto.ExecuteResponse;
import com.codex.agent.service.ExecutionRunner;
import com.github.dockerjava.api.DockerClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The single-endpoint API of the executor agent.
 *
 * <p>Exposed paths:
 * <ul>
 *   <li>{@code POST /v1/execute} — run a submission, return verdict + per-test results</li>
 *   <li>{@code GET  /v1/healthz} — liveness + Docker daemon ping + free disk in temp dir</li>
 *   <li>{@code GET  /v1/version} — agent version info</li>
 * </ul>
 *
 * <p>Hardening:
 * <ul>
 *   <li><b>Concurrency cap</b> — a semaphore caps in-flight executions so a small EC2 box
 *   doesn't OOM under burst load. Tunable via {@code executor.agent.max-concurrent}.</li>
 *   <li><b>Idempotency</b> — repeated POSTs with the same {@code submissionId} return the
 *   cached result for 5 minutes instead of re-running. Insurance against backend retries.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Slf4j
public class ExecuteController {

    private final ExecutionRunner executionRunner;
    private final DockerClient dockerClient;

    @Value("${executor.agent.max-concurrent:1}")
    private int maxConcurrent;

    @Value("${execution.temp-dir:/tmp/codex}")
    private String tempBaseDir;

    @Value("${executor.agent.version:dev}")
    private String agentVersion;

    /** Acquired per request. Initialized lazily once we know maxConcurrent. */
    private volatile Semaphore concurrencyLimiter;

    /**
     * Tiny LRU-ish idempotency cache: submissionId -> (response, insertedAtEpochMs).
     * 5-minute TTL, capped at 256 entries to bound memory. Cheaply
     * synchronized — submission rate is low and entries are small.
     */
    private static final long IDEMPOTENCY_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 256;
    private final Map<UUID, CachedResult> idempotencyCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, CachedResult> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@Valid @RequestBody ExecuteRequest request) {
        UUID submissionId = request.getSubmissionId();

        // Idempotency replay
        CachedResult cached = getCached(submissionId);
        if (cached != null) {
            log.info("Returning cached result for submission {} (idempotency replay)", submissionId);
            return ResponseEntity.ok(cached.response);
        }

        Semaphore limiter = limiter();
        boolean acquired;
        try {
            acquired = limiter.tryAcquire(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "interrupted while waiting for concurrency slot"));
        }

        if (!acquired) {
            log.warn("Submission {} rejected: no concurrency slot available within 60s", submissionId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "executor saturated, retry later"));
        }

        try {
            ExecuteResponse response = executionRunner.run(request);
            putCache(submissionId, response);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Submission {} failed at controller layer", submissionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() == null ? "execution failed" : e.getMessage()));
        } finally {
            limiter.release();
        }
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> healthz() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());
        body.put("version", agentVersion);

        // Docker daemon ping
        try {
            dockerClient.pingCmd().exec();
            body.put("docker", "UP");
        } catch (Exception e) {
            body.put("docker", "DOWN");
            body.put("dockerError", e.getMessage());
            body.put("status", "DEGRADED");
        }

        // Disk free in temp dir
        try {
            File baseDir = new File(tempBaseDir);
            long usableBytes = baseDir.exists() ? baseDir.getUsableSpace() : -1;
            body.put("tempDirFreeMb", usableBytes / 1024 / 1024);
        } catch (Exception e) {
            body.put("tempDirFreeMb", -1);
        }

        // Concurrency state
        Semaphore l = limiter();
        body.put("concurrency", Map.of(
                "max", maxConcurrent,
                "available", l.availablePermits()));

        return ResponseEntity.ok(body);
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
                "agent", agentVersion,
                "api", "v1"));
    }

    // ───── helpers ──────────────────────────────────────────────────────

    private Semaphore limiter() {
        Semaphore l = concurrencyLimiter;
        if (l == null) {
            synchronized (this) {
                if (concurrencyLimiter == null) {
                    int max = Math.max(1, maxConcurrent);
                    concurrencyLimiter = new Semaphore(max, true);
                    log.info("Executor agent concurrency limiter initialized with {} permits", max);
                }
                l = concurrencyLimiter;
            }
        }
        return l;
    }

    private CachedResult getCached(UUID submissionId) {
        synchronized (idempotencyCache) {
            CachedResult entry = idempotencyCache.get(submissionId);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.insertedAtMs > IDEMPOTENCY_TTL_MS) {
                idempotencyCache.remove(submissionId);
                return null;
            }
            return entry;
        }
    }

    private void putCache(UUID submissionId, ExecuteResponse response) {
        synchronized (idempotencyCache) {
            idempotencyCache.put(submissionId, new CachedResult(response, System.currentTimeMillis()));
        }
    }

    private record CachedResult(ExecuteResponse response, long insertedAtMs) {
    }
}
