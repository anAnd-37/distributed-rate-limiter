package com.anand.ratelimiter.service;

import com.anand.ratelimiter.model.KeyType;
import com.anand.ratelimiter.model.RateLimitResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Core rate limiting service.
 *
 * THIS IS THE MOST IMPORTANT CLASS IN THE PROJECT.
 *
 * WHAT IT DOES:
 * 1. Builds the Redis key from KeyType + identifier + endpoint
 * 2. Executes the Lua script atomically in Redis
 * 3. Parses the List<Long> result back into a RateLimitResult
 * 4. Handles Redis failures gracefully via fallback policy
 * 5. Records Prometheus metrics for every allow/deny decision
 *
 * INTERVIEW POINT — the atomicity guarantee:
 * redisTemplate.execute(script, keys, args) translates to Redis EVALSHA.
 * The entire Lua script runs as a single Redis command — atomic by design.
 * Redis is single-threaded, so while the Lua script executes, no other
 * command can run. This eliminates the TOCTOU race condition that would
 * exist if we used separate GET → compute → SET commands.
 *
 * INTERVIEW POINT — why not use transactions (MULTI/EXEC)?
 * Redis transactions with WATCH/MULTI/EXEC require optimistic locking —
 * if another client modifies the watched key, the transaction aborts and
 * you retry. Under high concurrency this causes many retries and
 * unpredictable latency. Lua scripts have no retries — they just work.
 */
@Slf4j
@Service
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List>       rateLimiterScript;
    private final String                         fallbackPolicy;

    // Prometheus counters — incremented on every allow/deny decision
    private final Counter allowedCounter;
    private final Counter deniedCounter;
    private final Counter fallbackCounter;

    // Key prefix — namespaces all rate limit keys in Redis
    // Makes it easy to scan/delete all rate limit keys: SCAN rl:*
    private static final String KEY_PREFIX = "rl";

    public RateLimiterService(
            RedisTemplate<String, String> redisTemplate,
            DefaultRedisScript<List> rateLimiterScript,
            @Value("${app.rate-limit.fallback:ALLOW}") String fallbackPolicy,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate     = redisTemplate;
        this.rateLimiterScript = rateLimiterScript;
        this.fallbackPolicy    = fallbackPolicy;

        // Register Prometheus counters at startup
        // These become metrics like: rate_limiter_requests_total{decision="allowed"}
        this.allowedCounter = Counter.builder("rate_limiter_requests_total")
                .tag("decision", "allowed")
                .description("Total requests allowed by the rate limiter")
                .register(meterRegistry);

        this.deniedCounter = Counter.builder("rate_limiter_requests_total")
                .tag("decision", "denied")
                .description("Total requests denied by the rate limiter")
                .register(meterRegistry);

        this.fallbackCounter = Counter.builder("rate_limiter_requests_total")
                .tag("decision", "fallback")
                .description("Total requests handled by fallback (Redis unavailable)")
                .register(meterRegistry);
    }

    /**
     * Main entry point — checks if a request is allowed.
     *
     * @param keyType    how to key the limit (USER, IP, API_KEY, COMPOSITE)
     * @param identifier the actual value (userId, IP address, or API key)
     * @param endpoint   which endpoint is being rate limited
     * @param limit      max requests allowed in the window
     * @param windowSecs time window in seconds
     * @param refillRate tokens added per second (-1 = use limit/window)
     * @return RateLimitResult with allow/deny decision + metadata
     */
    public RateLimitResult checkRateLimit(
            KeyType keyType,
            String  identifier,
            String  endpoint,
            int     limit,
            int     windowSecs,
            double  refillRate
    ) {
        // Resolve refill rate — if not specified, use natural rate (limit/window)
        double actualRefillRate = (refillRate <= 0)
                ? (double) limit / windowSecs
                : refillRate;

        if (keyType == KeyType.COMPOSITE) {
            return checkComposite(identifier, endpoint, limit, windowSecs, actualRefillRate);
        }

        String redisKey = buildKey(keyType, identifier, endpoint);
        return executeLuaScript(redisKey, limit, actualRefillRate, windowSecs);
    }

    /**
     * COMPOSITE mode — checks all three dimensions independently.
     * Request is allowed only if ALL three checks pass.
     * If any dimension is denied, the most restrictive result is returned.
     *
     * INTERVIEW POINT:
     * "Why check all three even if the first fails?"
     * We still want to consume tokens on the USER counter even if IP is
     * denied — otherwise a shared NAT IP denial would effectively give
     * users unlimited attempts by not consuming their personal quota.
     * Actually in practice, we short-circuit on first denial to avoid
     * unnecessary Redis calls. Both approaches are valid — discuss tradeoffs.
     */
    private RateLimitResult checkComposite(
            String identifier,
            String endpoint,
            int    limit,
            int    windowSecs,
            double refillRate
    ) {
        // Check user dimension first (most specific)
        String userKey = buildKey(KeyType.USER, identifier, endpoint);
        RateLimitResult userResult = executeLuaScript(userKey, limit, refillRate, windowSecs);
        if (!userResult.allowed()) {
            deniedCounter.increment();
            return userResult;
        }

        // Check IP dimension
        String ipKey = buildKey(KeyType.IP, identifier, endpoint);
        RateLimitResult ipResult = executeLuaScript(ipKey, limit * 10, refillRate * 10, windowSecs);
        if (!ipResult.allowed()) {
            deniedCounter.increment();
            return ipResult;
        }

        allowedCounter.increment();
        return userResult;
    }

    /**
     * Executes the Lua script in Redis and parses the result.
     *
     * SCRIPT ARGUMENTS:
     * KEYS[1] = redisKey      (e.g. "rl:user:u123:search")
     * ARGV[1] = capacity      (max tokens = limit)
     * ARGV[2] = refillRate    (tokens per second)
     * ARGV[3] = now           (current time in milliseconds)
     * ARGV[4] = ttl           (key expiry = windowSecs)
     *
     * SCRIPT RETURN:
     * [0] = 1 (allowed) or 0 (denied)
     * [1] = remaining tokens
     * [2] = ms until next token (0 if allowed)
     *
     * INTERVIEW POINT — parsing the result:
     * Redis returns Lua arrays as Java List<Object>.
     * Each element is a Long (Redis integers map to Java Long).
     * We compare [0] == 1L (not == 1, because it's Long not int).
     */
    @SuppressWarnings("unchecked")
    private RateLimitResult executeLuaScript(
            String redisKey,
            int    limit,
            double refillRate,
            int    windowSecs
    ) {
        try {
            long nowMs = System.currentTimeMillis();

            List<Long> result = (List<Long>) redisTemplate.execute(
                    rateLimiterScript,
                    Collections.singletonList(redisKey),   // KEYS
                    String.valueOf(limit),                  // ARGV[1] capacity
                    String.valueOf(refillRate),             // ARGV[2] refill rate
                    String.valueOf(nowMs),                  // ARGV[3] current time ms
                    String.valueOf(windowSecs)              // ARGV[4] TTL
            );

            if (result == null || result.size() < 3) {
                log.error("Unexpected null/empty result from Lua script for key: {}", redisKey);
                return applyFallback(limit, redisKey);
            }

            boolean allowed        = result.get(0) == 1L;
            long    remaining      = result.get(1);
            long    msUntilNext    = result.get(2);

            if (allowed) {
                allowedCounter.increment();
                log.debug("ALLOWED key={} remaining={}/{}", redisKey, remaining, limit);
                return RateLimitResult.allowed(remaining, limit, redisKey);
            } else {
                deniedCounter.increment();
                log.debug("DENIED  key={} retryAfterMs={}", redisKey, msUntilNext);
                return RateLimitResult.denied(msUntilNext, limit, redisKey);
            }

        } catch (Exception e) {
            // Redis is unavailable — circuit breaker would normally handle this
            // but we also handle it here as a safety net
            log.error("Redis error for key {}: {}. Applying fallback: {}",
                    redisKey, e.getMessage(), fallbackPolicy);
            fallbackCounter.increment();
            return applyFallback(limit, redisKey);
        }
    }

    /**
     * Builds the Redis key from the dimension type and identifier.
     *
     * KEY FORMAT: rl:{dimension}:{identifier}:{endpoint}
     *
     * Examples:
     *   rl:user:u123:search        — user u123 on search endpoint
     *   rl:ip:192.168.1.1:login    — IP on login endpoint
     *   rl:apikey:key-abc:upload   — API key on upload endpoint
     *
     * INTERVIEW POINT:
     * "Why include the endpoint in the key?"
     * Without endpoint namespacing, a user's search limit and upload
     * limit share the same counter. They'd exhaust each other.
     * Each endpoint gets its own independent bucket per user.
     */
    private String buildKey(KeyType keyType, String identifier, String endpoint) {
        String dimension = switch (keyType) {
            case USER    -> "user";
            case IP      -> "ip";
            case API_KEY -> "apikey";
            default      -> "user";
        };
        // Sanitize identifier — remove characters that break Redis key parsing
        String safeIdentifier = identifier.replaceAll("[^a-zA-Z0-9._-]", "_");
        String safeEndpoint   = endpoint.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        return String.format("%s:%s:%s:%s", KEY_PREFIX, dimension, safeIdentifier, safeEndpoint);
    }

    /**
     * Fallback when Redis is unavailable.
     *
     * ALLOW (fail-open)  — request goes through, availability preserved
     * DENY  (fail-closed) — request blocked, safety preserved
     *
     * INTERVIEW POINT:
     * "Which should you use?"
     * Depends on your threat model. For a hotel search API, a brief Redis
     * outage shouldn't block users from searching — use ALLOW.
     * For a payment API, you'd rather reject traffic than risk unbounded
     * requests during an outage — use DENY.
     */
    private RateLimitResult applyFallback(int limit, String redisKey) {
        if ("DENY".equalsIgnoreCase(fallbackPolicy)) {
            return RateLimitResult.denied(5000L, limit, redisKey);
        }
        return RateLimitResult.fallback(limit, redisKey);
    }

}