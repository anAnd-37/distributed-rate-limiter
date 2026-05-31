package com.anand.ratelimiter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Application entry point.
 *
 * @EnableAspectJAutoProxy — activates Spring AOP support.
 * Without this annotation, the @Aspect class is loaded as a bean
 * but its @Around advice NEVER fires — the rate limiter silently
 * does nothing and all requests go through unthrottled.
 *
 * INTERVIEW POINT:
 * "What does @EnableAspectJAutoProxy do?"
 * It tells Spring to create proxy wrappers around beans that have
 * matching @Pointcut definitions. When RateLimitAspect's pointcut
 * matches a method, Spring replaces the real bean with a proxy that
 * calls the aspect advice first, then (optionally) the real method.
 * proxyTargetClass = true forces CGLIB proxies (subclass-based)
 * instead of JDK dynamic proxies (interface-based) — more compatible
 * with Spring Boot's autowiring.
 */
@Slf4j
@SpringBootApplication
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class RateLimiterApplication {

    public static void main(String[] args) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info(" Starting Distributed API Rate Limiter");
        log.info(" Built by: Anand Sahu");
        log.info(" Stack: Spring Boot + Redis Lua + AOP + Prometheus");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        SpringApplication.run(RateLimiterApplication.class, args);

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info(" Application started successfully!");
        log.info(" API Base URL : http://localhost:8080");
        log.info(" Endpoints    : http://localhost:8080/auth/endpoints");
        log.info(" Health       : http://localhost:8080/actuator/health");
        log.info(" Metrics      : http://localhost:8080/actuator/prometheus");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

}