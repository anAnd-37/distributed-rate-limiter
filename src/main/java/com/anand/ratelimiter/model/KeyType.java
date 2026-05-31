package com.anand.ratelimiter.model;

/**
 * Defines the dimension along which rate limiting is applied.
 *
 * WHY THIS EXISTS:
 * A single "limit by IP" approach breaks in office environments where
 * 500 employees share one NAT IP. A single "limit by user" approach
 * can't protect unauthenticated endpoints. We need both — and sometimes
 * all three checked independently (COMPOSITE).
 *
 * INTERVIEW POINT:
 * "Why not just limit by IP?" — Because one bad actor on a shared IP
 * would block all legitimate users behind that same NAT gateway.
 * Multi-dimensional keying solves this by giving each dimension
 * its own independent counter.
 */
public enum KeyType {

    /**
     * Rate limit per authenticated user.
     * Redis key format: rl:user:{userId}:{endpoint}
     * Use for: authenticated API endpoints
     * Example: user123 gets 100 search requests/min regardless of which
     *          app server or IP they're connecting from.
     */
    USER,

    /**
     * Rate limit per client IP address.
     * Redis key format: rl:ip:{clientIP}:{endpoint}
     * Use for: unauthenticated endpoints (login, signup, forgot-password)
     * Example: prevents brute-force attacks where attacker
     *          hammers login endpoint from one IP.
     */
    IP,

    /**
     * Rate limit per API key (for B2B / third-party integrations).
     * Redis key format: rl:apikey:{apiKey}:{endpoint}
     * Use for: partner integrations with custom quota per contract
     * Example: AggregatorX gets 10,000 req/min, AggregatorY gets 1,000.
     */
    API_KEY,

    /**
     * Checks ALL three dimensions independently.
     * A request is allowed only if ALL three checks pass.
     * Redis key format: all three keys checked in sequence
     * Use for: high-security endpoints where you want layered protection
     * Example: payment endpoints — must pass user limit AND ip limit AND apikey limit.
     */
    COMPOSITE

}