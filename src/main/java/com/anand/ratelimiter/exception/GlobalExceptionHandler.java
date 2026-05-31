package com.anand.ratelimiter.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts exceptions into proper HTTP responses.
 *
 * WHY @RestControllerAdvice:
 * A single centralized place for all exception-to-HTTP mapping.
 * Without this, Spring returns a generic 500 error page for
 * unhandled exceptions. With this, every exception maps to a
 * specific, well-structured HTTP response.
 *
 * INTERVIEW POINT:
 * "How does the 429 response get built?"
 * The AOP aspect throws RateLimitExceededException.
 * Spring's DispatcherServlet catches it and routes it here.
 * We build the response with the correct status, headers, and body.
 * The client gets a complete, actionable 429 response — not a 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles rate limit exceeded — returns HTTP 429 Too Many Requests.
     *
     * RESPONSE HEADERS (industry standard):
     * Retry-After          — seconds until the client can retry
     * X-RateLimit-Limit    — the configured maximum
     * X-RateLimit-Remaining — tokens left (0 when denied)
     * X-RateLimit-Reset    — Unix timestamp when the window resets
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(
            RateLimitExceededException ex,
            HttpServletRequest request
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After",           String.valueOf(ex.getRetryAfterSeconds()));
        headers.add("X-RateLimit-Limit",     String.valueOf(ex.getLimit()));
        headers.add("X-RateLimit-Remaining", String.valueOf(ex.getRemainingTokens()));
        headers.add("X-RateLimit-Reset",
                String.valueOf(Instant.now().getEpochSecond() + ex.getRetryAfterSeconds()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",      429);
        body.put("error",       "Too Many Requests");
        body.put("message",     ex.getMessage());
        body.put("retryAfter",  ex.getRetryAfterSeconds() + " seconds");
        body.put("path",        request.getRequestURI());
        body.put("timestamp",   Instant.now().toString());

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(body);
    }

    /**
     * Catch-all for any unexpected exceptions.
     * Returns HTTP 500 with a clean JSON body instead of a stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(
            Exception ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",    500);
        body.put("error",     "Internal Server Error");
        body.put("message",   ex.getMessage());
        body.put("path",      request.getRequestURI());
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }

}