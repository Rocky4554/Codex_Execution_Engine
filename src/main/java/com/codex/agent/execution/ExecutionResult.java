package com.codex.agent.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Internal raw result of running a single command inside a container.
 * Mirrors {@code com.codex.platform.execution.dto.ExecutionResult} on the
 * backend side. Kept private to the agent so the agent has no dependency on
 * the platform module.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {
    private String stdout;
    private String stderr;
    private Integer exitCode;
    private Long executionTimeMs;
    private boolean success;
    /** True when the command was cut off by the time limit. */
    private boolean timedOut;

    public ExecutionResult(String stdout, String stderr, Integer exitCode, Long executionTimeMs, boolean success) {
        this(stdout, stderr, exitCode, executionTimeMs, success, false);
    }
}
