package com.anand.ratelimiter.model;

/**
 * Carries the result of a single rate limit check.
 *
 * WHY A RECORD:
 * Java 16+ records are immutable data carriers with zero boilerplate.
 * No need to write getters, equals(), hashCode(), toString() manually.
 * Perfect for a pure data object like this that just flows between layers.
 *
 * INTERVIEW POINT:
 * "Why not just return a boolean?" — Because the caller needs more than
 * allowed/denied. The AOP aspect needs remainingTokens to set the
 * X-RateLimit-Remaining header, and needs retryAfterSeconds to set
 * the Retry-After header. Returning a rich object makes the API
 * self-documenting and avoids multiple Redis round-trips.
 *
 * DATA FLOW:
 * Lua script → List<Long> → RateLimiterService parses → RateLimitResult
 *           → RateLimitAspect reads → sets headers or throws exception
 */
public record RateLimitResult(

        /**
         * True if the request is allowed, false if rate limit exceeded.
         * Maps to Lua return value [1]: 1 = allowed, 0 = denied.
         */
        boolean allowed,

        /**
         * Number of tokens remaining in the bucket after this request.
         * Used to set the X-RateLimit-Remaining response header.
         * Maps to Lua return value [2].
         */
        long remainingTokens,

        /**
         * Milliseconds until the next token becomes available.
         * Only meaningful when allowed = false.
         * Converted to seconds for the Retry-After header.
         * Maps to Lua return value [3].
         */
        long msUntilNextToken,

        /**
         * The configured maximum tokens (bucket capacity).
         * Used to set the X-RateLimit-Limit response header.
         * Passed through from the @RateLimit annotation config.
         */
        long totalLimit,

        /**
         * The Redis key that was checked.
         * Useful for logging and debugging which dimension was exceeded.
         * Example: "rl:user:u123:search" or "rl:ip:192.168.1.1:login"
         */
        String redisKey

) {

    /**
     * Convenience method — seconds until retry (rounded up).
     * Used directly in the Retry-After HTTP header.
     */
    public long retryAfterSeconds() {
        return (long) Math.ceil(msUntilNextToken / 1000.0);
    }

    /**
     * Factory method for an allowed result — cleaner than calling constructor directly.
     */
    public static RateLimitResult allowed(long remaining, long total, String key) {
        return new RateLimitResult(true, remaining, 0, total, key);
    }

    /**
     * Factory method for a denied result.
     */
    public static RateLimitResult denied(long msUntilNext, long total, String key) {
        return new RateLimitResult(false, 0, msUntilNext, total, key);
    }

    /**
     * Factory method for fallback result when Redis is unavailable.
     * Used by the circuit breaker — defaults to allowing the request
     * to preserve availability (fail-open strategy).
     */
    public static RateLimitResult fallback(long total, String key) {
        return new RateLimitResult(true, total, 0, total, key);
    }

}