package com.codex.agent.execution;

/**
 * Verbatim copy of {@code com.codex.platform.common.util.OutputNormalizer}.
 * Both backend and agent must use the same normalization rules so a verdict
 * doesn't change based on which side did the comparison.
 */
public class OutputNormalizer {

    /**
     * Normalizes output for comparison:
     * - Trims leading/trailing whitespace
     * - Normalizes newlines to \n
     * - Removes trailing spaces from each line
     * - Removes empty lines at the end
     */
    public static String normalize(String output) {
        if (output == null) {
            return "";
        }

        String normalized = output.replace("\r\n", "\n").replace("\r", "\n");

        String[] lines = normalized.split("\n");
        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            sb.append(line.replaceAll("\\s+$", "")).append("\n");
        }

        return sb.toString().replaceAll("\n+$", "").trim();
    }

    /** Compares two outputs after normalization. */
    public static boolean areEqual(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }
}
