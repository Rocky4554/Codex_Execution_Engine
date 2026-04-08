package com.codex.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request payload for {@code POST /v1/execute}.
 * The Render-hosted backend constructs this from a queued submission and
 * sends it to the EC2-hosted agent.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteRequest {

    @NotNull
    private UUID submissionId;

    @NotBlank
    private String language;

    /** Custom executor image, e.g. {@code codex-cpp:latest}. */
    @NotBlank
    private String dockerImage;

    /** Null/blank for interpreted languages (Python, JavaScript). */
    private String compileCommand;

    @NotBlank
    private String executeCommand;

    /** File extension including the leading dot, e.g. {@code .cpp}. */
    @NotBlank
    private String fileExtension;

    /** UTF-8 source code, capped at 256 KB. */
    @NotBlank
    @Size(max = 256 * 1024, message = "Source code exceeds 256 KB")
    private String sourceCode;

    @Positive
    private int compileTimeoutMs = 60_000;

    @Positive
    private int runTimeoutMs = 5_000;

    @Positive
    private int memoryLimitMb = 256;

    @NotEmpty
    @Valid
    private List<TestCase> testCases;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestCase {
        @NotBlank
        private String id;

        private String stdin;

        private String expectedStdout;
    }
}
