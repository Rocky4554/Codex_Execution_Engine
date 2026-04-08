package com.codex.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Response payload for {@code POST /v1/execute}.
 * Status strings match {@code com.codex.platform.common.enums.SubmissionStatus}
 * on the backend side, so the backend can map them straight back into its
 * existing enum without translation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteResponse {

    private UUID submissionId;

    /**
     * One of: ACCEPTED, WRONG_ANSWER, COMPILATION_ERROR, RUNTIME_ERROR,
     * TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED.
     */
    private String status;

    private String compileOutput;
    private long compileTimeMs;
    private long totalExecTimeMs;

    /** Number of test cases that passed (matches platform's submission_results.passedTestCases). */
    private int passedTestCases;
    private int totalTestCases;

    /** Concatenated stdout/stderr across compile + all run attempts (matches existing behaviour). */
    private String stdout;
    private String stderr;

    private List<TestResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestResult {
        private String testCaseId;

        /** PASSED, FAILED, TIME_LIMIT_EXCEEDED, RUNTIME_ERROR, MEMORY_LIMIT_EXCEEDED, SKIPPED. */
        private String status;

        private String stdout;
        private String stderr;
        private long execTimeMs;
        private int exitCode;
    }
}
