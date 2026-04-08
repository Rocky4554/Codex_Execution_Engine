package com.codex.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Minimal bearer-token auth for the executor agent.
 *
 * <p>The token is shared between Render (backend) and EC2 (agent) via the
 * {@code EXECUTOR_AGENT_TOKEN} env var. This filter is the only thing standing
 * between the public internet and arbitrary code execution, so the production
 * setup pairs this with an EC2 security group that limits port 443 ingress to
 * Render's egress IPs. The token alone is NOT sufficient defense.
 *
 * <p>Health/version endpoints are exempted so platform monitors can probe them
 * without needing the secret.
 */
@Component
@Slf4j
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${executor.agent.token:}")
    private String configuredToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Public endpoints — no token required
        if (path.startsWith("/v1/healthz")
                || path.startsWith("/v1/version")
                || path.startsWith("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }

        // Reject startup with no token configured — fails closed.
        if (configuredToken == null || configuredToken.isBlank()) {
            log.error("EXECUTOR_AGENT_TOKEN is not configured; rejecting all authenticated requests");
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "executor agent token not configured");
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "missing bearer token");
            return;
        }

        String presented = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (!constantTimeEquals(presented, configuredToken)) {
            log.warn("Rejected request to {} with invalid bearer token", path);
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "invalid bearer token");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Constant-time comparison to avoid timing-side-channel leaks of the token.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
