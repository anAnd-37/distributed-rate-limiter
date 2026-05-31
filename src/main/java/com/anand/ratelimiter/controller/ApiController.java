package com.anand.ratelimiter.controller;

import com.anand.ratelimiter.annotation.RateLimit;
import com.anand.ratelimiter.model.KeyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo API endpoints — each with a different @RateLimit configuration.
 *
 * PURPOSE:
 * These endpoints exist purely to demonstrate and test the rate limiter.
 * Each endpoint has a deliberately different limit config so you can
 * observe different behaviours in Postman.
 *
 * INTERVIEW POINT:
 * Notice how clean the controllers are — zero rate limiting logic here.
 * Adding @RateLimit is the only thing needed. The AOP aspect handles
 * everything else: Redis call, header setting, 429 response.
 * This is the power of declarative rate limiting via AOP.
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ApiController {

    /**
     * ENDPOINT 1: Search — 10 requests per 60 seconds per USER.
     * Deliberately low limit (10) so you can hit it quickly in Postman.
     *
     * Test: fire 11 rapid requests → first 10 return 200, 11th returns 429.
     */
    @RateLimit(
            limit    = 10,
            window   = 60,
            keyType  = KeyType.USER,
            endpoint = "search",
            fallback = "ALLOW"
    )
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(defaultValue = "hotels") String query,
            Authentication auth
    ) {
        String userId = auth != null ? auth.getName() : "anonymous";
        log.info("[API] GET /api/search query='{}' userId='{}'", query, userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint",   "search");
        response.put("query",      query);
        response.put("userId",     userId);
        response.put("results",    generateMockResults(query));
        response.put("timestamp",  Instant.now().toString());
        response.put("message",    "Search executed successfully");

        log.info("[API] Search response sent for userId='{}'", userId);
        return ResponseEntity.ok(response);
    }

    /**
     * ENDPOINT 2: Data — 5 requests per 30 seconds per USER.
     * Even lower limit — hits 429 faster.
     *
     * Test: fire 6 rapid requests → 5th returns 200, 6th returns 429.
     */
    @RateLimit(
            limit    = 5,
            window   = 30,
            keyType  = KeyType.USER,
            endpoint = "data",
            fallback = "ALLOW"
    )
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getData(Authentication auth) {
        String userId = auth != null ? auth.getName() : "anonymous";
        log.info("[API] GET /api/data userId='{}'", userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint",  "data");
        response.put("userId",    userId);
        response.put("data",      Map.of("records", 42, "page", 1));
        response.put("timestamp", Instant.now().toString());
        response.put("message",   "Data fetched successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * ENDPOINT 3: Upload — 3 requests per 60 seconds per IP.
     * Keyed by IP — good for demonstrating IP-based limiting.
     * Very strict limit (3) so it's easy to trigger in Postman.
     *
     * Test: fire 4 requests → 3rd returns 200, 4th returns 429.
     */
    @RateLimit(
            limit    = 3,
            window   = 60,
            keyType  = KeyType.IP,
            endpoint = "upload",
            fallback = "DENY"
    )
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestBody(required = false) Map<String, Object> payload,
            Authentication auth
    ) {
        String userId = auth != null ? auth.getName() : "anonymous";
        log.info("[API] POST /api/upload userId='{}' payload={}", userId, payload);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint",  "upload");
        response.put("userId",    userId);
        response.put("status",    "uploaded");
        response.put("fileSize",  payload != null ? payload.getOrDefault("size", "unknown") : "unknown");
        response.put("timestamp", Instant.now().toString());
        response.put("message",   "Upload successful");

        log.info("[API] Upload successful for userId='{}'", userId);
        return ResponseEntity.ok(response);
    }

    /**
     * ENDPOINT 4: Status — very generous limit, mostly for health checking.
     * 100 requests per 60 seconds per USER.
     */
    @RateLimit(
            limit    = 100,
            window   = 60,
            keyType  = KeyType.USER,
            endpoint = "status"
    )
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(Authentication auth) {
        String userId = auth != null ? auth.getName() : "anonymous";
        log.info("[API] GET /api/status userId='{}'", userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint",   "status");
        response.put("status",     "UP");
        response.put("userId",     userId);
        response.put("timestamp",  Instant.now().toString());
        response.put("message",    "Service is healthy");

        return ResponseEntity.ok(response);
    }

    /**
     * ENDPOINT 5: Premium — COMPOSITE keying (user + IP checked independently).
     * Demonstrates multi-dimensional rate limiting.
     * 20 requests per 60 seconds — checked across USER and IP both.
     */
    @RateLimit(
            limit    = 20,
            window   = 60,
            keyType  = KeyType.COMPOSITE,
            endpoint = "premium",
            fallback = "DENY"
    )
    @GetMapping("/premium")
    public ResponseEntity<Map<String, Object>> premium(Authentication auth) {
        String userId = auth != null ? auth.getName() : "anonymous";
        log.info("[API] GET /api/premium userId='{}' (COMPOSITE rate limit)", userId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpoint",  "premium");
        response.put("userId",    userId);
        response.put("tier",      "premium");
        response.put("timestamp", Instant.now().toString());
        response.put("message",   "Premium content accessed — composite rate limit applied");

        return ResponseEntity.ok(response);
    }

    // ── Mock data generator ──────────────────────────────────────────────────
    private Object generateMockResults(String query) {
        return Map.of(
                "total",   3,
                "results", java.util.List.of(
                        Map.of("id", 1, "name", "Hotel Alpha — " + query, "price", 2500),
                        Map.of("id", 2, "name", "Hotel Beta — "  + query, "price", 3200),
                        Map.of("id", 3, "name", "Hotel Gamma — " + query, "price", 1800)
                )
        );
    }

}