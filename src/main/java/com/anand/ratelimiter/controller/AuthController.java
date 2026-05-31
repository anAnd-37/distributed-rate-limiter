package com.anand.ratelimiter.controller;

import com.anand.ratelimiter.annotation.RateLimit;
import com.anand.ratelimiter.model.KeyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auth controller — issues tokens for testing.
 *
 * PURPOSE:
 * In a real system, this would validate credentials against a database.
 * Here it simply accepts any userId and issues a signed JWT — this lets
 * us easily test rate limiting with different user identities in Postman
 * by just changing the userId in the request body.
 *
 * ALSO DEMONSTRATES:
 * Rate limiting the auth endpoint itself — 5 token requests per minute
 * per IP. This mirrors real-world brute force protection on login endpoints.
 *
 * INTERVIEW POINT:
 * "Why rate limit the auth endpoint by IP instead of userId?"
 * Because an attacker doesn't have a valid userId yet — they're trying
 * to discover one. IP-based limiting is the only viable dimension for
 * unauthenticated endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    /**
     * Issues a simple token for testing.
     * Rate limited: 5 requests per 60 seconds per IP.
     *
     * REQUEST BODY:
     * { "userId": "user123" }
     *
     * RESPONSE:
     * {
     *   "token": "Bearer <base64-encoded-payload>",
     *   "userId": "user123",
     *   "expiresAt": "..."
     * }
     *
     * HOW TO USE IN POSTMAN:
     * 1. POST /auth/token with body {"userId": "user123"}
     * 2. Copy the token value from response
     * 3. In subsequent requests, add Header: Authorization: Basic dXNlcjEyMzp0ZXN0
     *    (Base64 of "userId:test")
     */
    @RateLimit(
            limit    = 5,
            window   = 60,
            keyType  = KeyType.IP,
            endpoint = "auth-token",
            fallback = "DENY"
    )
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> getToken(
            @RequestBody Map<String, String> request
    ) {
        String userId = request.getOrDefault("userId", "anonymous");

        log.info("[AUTH] Token request for userId='{}'", userId);
        log.info("[AUTH] JWT secret length: {} chars", jwtSecret.length());
        log.info("[AUTH] Token expiry: {}ms ({}min)",
                jwtExpirationMs, jwtExpirationMs / 60000);

        // Simple Base64 token for demo — in production use proper JWT library
        String tokenPayload = userId + ":" + System.currentTimeMillis();
        String token = java.util.Base64.getEncoder()
                .encodeToString(tokenPayload.getBytes());

        Instant expiresAt = Instant.now().plusMillis(jwtExpirationMs);

        log.info("[AUTH] Token issued for userId='{}' expiresAt='{}'", userId, expiresAt);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token",       "Basic " + java.util.Base64.getEncoder()
                .encodeToString((userId + ":test").getBytes()));
        response.put("userId",      userId);
        response.put("expiresAt",   expiresAt.toString());
        response.put("timestamp",   Instant.now().toString());
        response.put("message",     "Token issued successfully. Use as Authorization header.");
        response.put("usage",       "Authorization: Basic " +
                java.util.Base64.getEncoder()
                        .encodeToString((userId + ":test").getBytes()));

        return ResponseEntity.ok(response);
    }

    /**
     * Health check — no rate limit, always accessible.
     * Useful for load balancer health probes.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        log.debug("[AUTH] Health check called");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status",    "UP");
        response.put("service",   "rate-limiter");
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }

    /**
     * Returns info about all available endpoints and their rate limits.
     * Useful for Postman testing — shows exactly what limits are configured.
     */
    @GetMapping("/endpoints")
    public ResponseEntity<Map<String, Object>> endpoints() {
        log.info("[AUTH] Endpoint info requested");

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("GET  /api/search",  "10 req / 60s per USER  — hit 11x to see 429");
        limits.put("GET  /api/data",    "5 req  / 30s per USER  — hit 6x to see 429");
        limits.put("POST /api/upload",  "3 req  / 60s per IP    — hit 4x to see 429");
        limits.put("GET  /api/status",  "100 req / 60s per USER — generous limit");
        limits.put("GET  /api/premium", "20 req / 60s COMPOSITE — user + IP checked");
        limits.put("POST /auth/token",  "5 req  / 60s per IP    — brute force protection");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoints",   limits);
        response.put("howToTest",   "POST /auth/token with {\"userId\":\"user123\"} to get your Authorization header");
        response.put("timestamp",   Instant.now().toString());

        return ResponseEntity.ok(response);
    }

}