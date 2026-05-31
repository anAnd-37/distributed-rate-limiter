package com.anand.ratelimiter.annotation;

import com.anand.ratelimiter.model.KeyType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative rate limiting annotation.
 *
 * USAGE EXAMPLE:
 *   @GetMapping("/api/search")
 *   @RateLimit(limit = 100, window = 60, keyType = KeyType.USER)
 *   public ResponseEntity<String> search() { ... }
 *
 * That single annotation protects the endpoint — zero rate limiting
 * logic inside the controller. The AOP aspect handles everything.
 *
 * INTERVIEW POINT:
 * "Why an annotation instead of calling a service directly?"
 * Because rate limiting is a cross-cutting concern — it applies
 * to many methods across many classes. Hardcoding a service call
 * in every controller violates DRY and pollutes business logic.
 * An annotation + AOP means you add/remove rate limiting by
 * adding/removing one line, with zero changes to controller logic.
 *
 * @Target(METHOD)     — can only be placed on methods, not classes
 * @Retention(RUNTIME) — annotation is visible at runtime via reflection
 *                       (required for AOP to read it)
 * @Documented         — shows up in generated Javadoc
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * Maximum number of requests allowed within the time window.
     * This is the bucket capacity in the token bucket algorithm.
     *
     * Example: limit = 100 means 100 requests per window seconds.
     * Default: 100 requests
     */
    int limit() default 100;

    /**
     * Time window in seconds over which the limit applies.
     * Also used as the Redis key TTL — key auto-expires after
     * this many seconds of inactivity.
     *
     * Example: window = 60 means 100 requests per 60 seconds.
     * Default: 60 seconds
     */
    int window() default 60;

    /**
     * Refill rate — tokens added per second.
     * Controls how quickly the bucket refills after being emptied.
     *
     * INTERVIEW POINT — the refill rate math:
     * If limit=100 and window=60, a natural refill rate is 100/60 ≈ 1.67 tokens/sec.
     * This means after 30 seconds of silence, ~50 tokens are restored.
     * Setting refillRate lower than limit/window makes the limiter stricter.
     * Setting it higher allows faster burst recovery.
     *
     * Default: limit / window (natural even refill)
     * -1 means "use default calculation" (limit / window)
     */
    double refillRate() default -1;

    /**
     * Dimension along which to apply the rate limit.
     * USER    = per authenticated user (most common)
     * IP      = per client IP (for unauthenticated endpoints)
     * API_KEY = per API key (for B2B integrations)
     * COMPOSITE = all three checked independently
     *
     * Default: USER
     */
    KeyType keyType() default KeyType.USER;

    /**
     * Human-readable name for this endpoint.
     * Used as part of the Redis key to namespace limits per endpoint.
     * If empty, the method name is used automatically.
     *
     * Example: endpoint = "search" produces key "rl:user:u123:search"
     * Default: "" (uses method name)
     */
    String endpoint() default "";

    /**
     * Fallback behaviour when Redis is unavailable.
     * ALLOW = fail-open  (allow all requests, preserve availability)
     * DENY  = fail-closed (deny all requests, preserve safety)
     *
     * INTERVIEW POINT:
     * "What happens when Redis goes down?"
     * The circuit breaker opens and this fallback kicks in.
     * ALLOW is the right default for most APIs — a brief Redis outage
     * shouldn't take down your entire service.
     * DENY is appropriate for payment or security-critical endpoints
     * where you'd rather reject traffic than risk abuse.
     *
     * Default: "ALLOW" (fail-open)
     */
    String fallback() default "ALLOW";

}