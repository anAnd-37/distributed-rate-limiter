package com.anand.ratelimiter.aspect;

import com.anand.ratelimiter.annotation.RateLimit;
import com.anand.ratelimiter.exception.RateLimitExceededException;
import com.anand.ratelimiter.model.KeyType;
import com.anand.ratelimiter.model.RateLimitResult;
import com.anand.ratelimiter.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Objects;

/**
 * AOP Aspect — the bridge between @RateLimit annotation and RateLimiterService.
 *
 * HOW AOP WORKS HERE:
 * 1. Developer annotates a method with @RateLimit
 * 2. Spring wraps that method with a proxy at startup
 * 3. When the method is called, this aspect's @Around advice fires FIRST
 * 4. Aspect extracts annotation config, builds identifier, calls service
 * 5. If allowed  → calls joinPoint.proceed() → original method executes
 * 6. If denied   → throws RateLimitExceededException → GlobalExceptionHandler returns 429
 *
 * INTERVIEW POINT — why @Around and not @Before?
 * @Before can intercept but cannot stop execution or modify the return value.
 * @Around wraps the entire method — we can prevent execution (by not calling
 * joinPoint.proceed()), modify arguments, modify the return value, or
 * measure execution time. For rate limiting we need to STOP execution
 * when denied, so @Around is the only correct choice.
 *
 * INTERVIEW POINT — why AOP over a servlet filter?
 * Servlet filters fire for every request at the HTTP layer — they only see
 * HttpServletRequest, not Spring Security context or method metadata.
 * AOP fires at the method level — it has access to:
 *   - The exact method being called and its annotations
 *   - Spring Security context (authenticated userId)
 *   - Method parameters (could key by a parameter value)
 * This makes per-endpoint, per-user limits trivially easy.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimiterService rateLimiterService;

    // =========================================================
    // POINTCUT — defines WHICH methods this aspect applies to
    // =========================================================

    /**
     * Matches any method annotated with @RateLimit anywhere in the application.
     * Spring AOP scans all beans at startup and wraps matching methods with proxies.
     */
    @Pointcut("@annotation(com.anand.ratelimiter.annotation.RateLimit)")
    public void rateLimitedMethods() {}

    // =========================================================
    // ADVICE — the actual logic that runs around matching methods
    // =========================================================

    /**
     * Main advice — fires around every @RateLimit annotated method.
     *
     * EXECUTION FLOW:
     * ┌─────────────────────────────────────────────┐
     * │  Request arrives at controller method        │
     * │  ↓                                           │
     * │  @Around advice intercepts                   │
     * │  ↓                                           │
     * │  Extract annotation config                   │
     * │  Extract identifier (userId / IP / apiKey)   │
     * │  Build Redis key                             │
     * │  Call RateLimiterService → Redis Lua script  │
     * │  ↓                          ↓                │
     * │  ALLOWED                  DENIED             │
     * │  Set response headers     Throw 429          │
     * │  joinPoint.proceed()      Exception handler  │
     * │  Original method runs     Returns 429 JSON   │
     * └─────────────────────────────────────────────┘
     */
    @Around("rateLimitedMethods()")
    public Object enforceRateLimit(ProceedingJoinPoint joinPoint) throws Throwable {

        long aspectStartTime = System.currentTimeMillis();

        // ── STEP 1: Extract method signature and annotation config ──────────
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method          method    = signature.getMethod();
        RateLimit       rateLimit = method.getAnnotation(RateLimit.class);

        String className   = joinPoint.getTarget().getClass().getSimpleName();
        String methodName  = method.getName();
        String endpoint    = rateLimit.endpoint().isEmpty()
                ? methodName.toLowerCase()
                : rateLimit.endpoint();

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("[RATE-LIMIT] Intercepted: {}.{}()", className, methodName);
        log.info("[RATE-LIMIT] Endpoint    : {}", endpoint);
        log.info("[RATE-LIMIT] Config      : limit={}, window={}s, keyType={}, fallback={}",
                rateLimit.limit(), rateLimit.window(),
                rateLimit.keyType(), rateLimit.fallback());

        // ── STEP 2: Get HTTP request context ───────────────────────────────
        HttpServletRequest  request  = getCurrentRequest();
        HttpServletResponse response = getCurrentResponse();

        String clientIp = extractClientIp(request);
        log.info("[RATE-LIMIT] Client IP   : {}", clientIp);
        log.info("[RATE-LIMIT] Request URI : {} {}", request.getMethod(), request.getRequestURI());
        log.info("[RATE-LIMIT] User-Agent  : {}", request.getHeader("User-Agent"));
        log.info("[RATE-LIMIT] Timestamp   : {}", Instant.now());

        // ── STEP 3: Extract identifier based on KeyType ─────────────────────
        String identifier = resolveIdentifier(rateLimit.keyType(), clientIp);
        log.info("[RATE-LIMIT] KeyType     : {}", rateLimit.keyType());
        log.info("[RATE-LIMIT] Identifier  : {}", identifier);

        // ── STEP 4: Call RateLimiterService → executes Lua script ───────────
        log.debug("[RATE-LIMIT] Calling RateLimiterService → Redis Lua script...");
        long serviceCallStart = System.currentTimeMillis();

        RateLimitResult result = rateLimiterService.checkRateLimit(
                rateLimit.keyType(),
                identifier,
                endpoint,
                rateLimit.limit(),
                rateLimit.window(),
                rateLimit.refillRate()
        );

        long serviceCallMs = System.currentTimeMillis() - serviceCallStart;
        log.info("[RATE-LIMIT] Redis call  : {}ms", serviceCallMs);
        log.info("[RATE-LIMIT] Redis key   : {}", result.redisKey());
        log.info("[RATE-LIMIT] Decision    : {}", result.allowed() ? "ALLOWED ✓" : "DENIED  ✗");
        log.info("[RATE-LIMIT] Remaining   : {}/{} tokens", result.remainingTokens(), result.totalLimit());

        // ── STEP 5: Always set rate limit headers on response ───────────────
        // Set headers regardless of allow/deny — clients need this info
        if (response != null) {
            response.setHeader("X-RateLimit-Limit",     String.valueOf(result.totalLimit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
            response.setHeader("X-RateLimit-Reset",
                    String.valueOf(Instant.now().getEpochSecond() + rateLimit.window()));
            response.setHeader("X-RateLimit-Key",       result.redisKey());

            log.debug("[RATE-LIMIT] Headers set : X-RateLimit-Limit={}, X-RateLimit-Remaining={}, X-RateLimit-Reset={}",
                    result.totalLimit(), result.remainingTokens(),
                    Instant.now().getEpochSecond() + rateLimit.window());
        }

        // ── STEP 6: Allow or deny ────────────────────────────────────────────
        if (!result.allowed()) {
            long retryAfter = result.retryAfterSeconds();

            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.warn("[RATE-LIMIT] *** REQUEST DENIED — 429 TOO MANY REQUESTS ***");
            log.warn("[RATE-LIMIT] Identifier  : {}", identifier);
            log.warn("[RATE-LIMIT] Endpoint    : {}", endpoint);
            log.warn("[RATE-LIMIT] Limit       : {}", rateLimit.limit());
            log.warn("[RATE-LIMIT] Retry after : {}s", retryAfter);
            log.warn("[RATE-LIMIT] Redis key   : {}", result.redisKey());
            log.warn("[RATE-LIMIT] Client IP   : {}", clientIp);
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            if (response != null) {
                response.setHeader("Retry-After", String.valueOf(retryAfter));
            }

            throw new RateLimitExceededException(
                    retryAfter,
                    result.totalLimit(),
                    result.remainingTokens(),
                    result.redisKey()
            );
        }

        // ── STEP 7: Proceed — call the original controller method ────────────
        log.info("[RATE-LIMIT] Proceeding to execute: {}.{}()", className, methodName);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        long methodStartTime = System.currentTimeMillis();
        Object result2 = joinPoint.proceed();
        long methodExecMs = System.currentTimeMillis() - methodStartTime;

        long totalAspectMs = System.currentTimeMillis() - aspectStartTime;

        log.info("[RATE-LIMIT] Method completed in   : {}ms", methodExecMs);
        log.info("[RATE-LIMIT] Total aspect overhead : {}ms (redis={}ms + logic={}ms)",
                totalAspectMs, serviceCallMs, totalAspectMs - serviceCallMs);
        log.info("[RATE-LIMIT] Remaining tokens      : {}/{}", result.remainingTokens(), result.totalLimit());

        return result2;
    }

    // =========================================================
    // HELPER METHODS
    // =========================================================

    /**
     * Resolves the identifier to use for rate limit key construction.
     *
     * Resolution order:
     * USER    → authenticated userId from Spring Security context
     *           falls back to IP if not authenticated
     * IP      → client IP address (X-Forwarded-For aware)
     * API_KEY → X-API-Key header value
     * COMPOSITE → userId (all three checked inside service)
     */
    private String resolveIdentifier(KeyType keyType, String clientIp) {
        return switch (keyType) {
            case IP -> {
                log.debug("[RATE-LIMIT] Resolving identifier by IP: {}", clientIp);
                yield clientIp;
            }
            case API_KEY -> {
                HttpServletRequest req = getCurrentRequest();
                String apiKey = req != null ? req.getHeader("X-API-Key") : null;
                if (apiKey == null || apiKey.isBlank()) {
                    log.warn("[RATE-LIMIT] X-API-Key header missing — falling back to IP: {}", clientIp);
                    yield clientIp;
                }
                log.debug("[RATE-LIMIT] Resolving identifier by API key: {}...",
                        apiKey.substring(0, Math.min(8, apiKey.length())));
                yield apiKey;
            }
            default -> {
                // USER and COMPOSITE — extract from Spring Security context
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated()
                        && !"anonymousUser".equals(auth.getPrincipal())) {
                    String userId = auth.getName();
                    log.debug("[RATE-LIMIT] Resolving identifier by userId: {}", userId);
                    yield userId;
                }
                log.debug("[RATE-LIMIT] No authenticated user — falling back to IP: {}", clientIp);
                yield clientIp;
            }
        };
    }

    /**
     * Extracts real client IP — handles reverse proxies and load balancers.
     *
     * INTERVIEW POINT:
     * "How do you get the real client IP behind a load balancer?"
     * Load balancers add X-Forwarded-For header with the original client IP.
     * The header can contain a chain: "client, proxy1, proxy2".
     * We take the first value (leftmost) which is the original client IP.
     * Falling back to request.getRemoteAddr() for direct connections.
     */
    private String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            log.warn("[RATE-LIMIT] HttpServletRequest is null — cannot extract IP");
            return "unknown";
        }

        // Check proxy headers in order of reliability
        String[] ipHeaders = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR",
                "HTTP_X_FORWARDED",
                "HTTP_FORWARDED_FOR",
                "HTTP_FORWARDED"
        };

        for (String header : ipHeaders) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For can be "client, proxy1, proxy2" — take first
                String clientIp = ip.split(",")[0].trim();
                log.debug("[RATE-LIMIT] IP extracted from header '{}': {}", header, clientIp);
                return clientIp;
            }
        }

        String remoteAddr = request.getRemoteAddr();
        log.debug("[RATE-LIMIT] IP from getRemoteAddr(): {}", remoteAddr);
        return remoteAddr;
    }

    /**
     * Gets current HttpServletRequest from Spring's RequestContextHolder.
     * Works because Spring stores the current request in a ThreadLocal.
     */
    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest();
        } catch (IllegalStateException e) {
            log.warn("[RATE-LIMIT] Could not get current HttpServletRequest: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Gets current HttpServletResponse from Spring's RequestContextHolder.
     * Used to set X-RateLimit-* headers on the response before it's sent.
     */
    private HttpServletResponse getCurrentResponse() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getResponse();
        } catch (IllegalStateException e) {
            log.warn("[RATE-LIMIT] Could not get current HttpServletResponse: {}", e.getMessage());
            return null;
        }
    }

}