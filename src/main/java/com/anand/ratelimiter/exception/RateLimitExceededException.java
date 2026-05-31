package com.anand.ratelimiter.exception;

/**
 * Thrown by RateLimitAspect when a request exceeds the configured limit.
 *
 * WHY A CUSTOM EXCEPTION:
 * We need to carry metadata (retryAfter, limit, key) from the point
 * where the limit is detected (aspect) to the point where the HTTP
 * response is built (GlobalExceptionHandler). A custom exception is
 * the cleanest way to pass this context through Spring's exception
 * handling chain without coupling the layers together.
 *
 * INTERVIEW POINT:
 * "Why not just return a ResponseEntity<> directly from the aspect?"
 * Because the aspect sits below the controller layer — it doesn't
 * know about HTTP. Throwing an exception and letting the global
 * handler convert it to HTTP 429 keeps the layers clean.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final long limit;
    private final long remainingTokens;
    private final String redisKey;

    public RateLimitExceededException(
            long retryAfterSeconds,
            long limit,
            long remainingTokens,
            String redisKey
    ) {
        super(String.format(
                "Rate limit exceeded. Limit: %d requests. " +
                        "Retry after %d seconds. Key: %s",
                limit, retryAfterSeconds, redisKey
        ));
        this.retryAfterSeconds = retryAfterSeconds;
        this.limit             = limit;
        this.remainingTokens   = remainingTokens;
        this.redisKey          = redisKey;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
    public long getLimit()             { return limit; }
    public long getRemainingTokens()   { return remainingTokens; }
    public String getRedisKey()        { return redisKey; }

}