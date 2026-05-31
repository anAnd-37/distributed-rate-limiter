package com.anand.ratelimiter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

/**
 * Configures Redis infrastructure for the rate limiter.
 *
 * THREE THINGS THIS CLASS DOES:
 * 1. Creates the Redis connection using Lettuce (async, non-blocking driver)
 * 2. Creates a typed RedisTemplate for executing commands
 * 3. Loads the Lua script from classpath at startup — preloaded and ready
 *
 * WHY LETTUCE OVER JEDIS:
 * Lettuce is async and thread-safe — one connection handles many concurrent
 * requests via netty event loops. Jedis is synchronous and requires one
 * connection per thread, which is expensive at high concurrency.
 * At 10,000 req/sec, Lettuce uses ~10 connections; Jedis would need ~10,000.
 *
 * INTERVIEW POINT:
 * "Why load the Lua script at startup instead of sending it with each request?"
 * We could use EVAL (sends script every time) or EVALSHA (sends only the SHA hash).
 * Spring's DefaultRedisScript uses EVALSHA internally — it sends the script once,
 * Redis caches it and returns a SHA. Subsequent calls send only the 40-char SHA.
 * This reduces network payload on every rate limit check from ~500 bytes to 40 bytes.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    /**
     * Lettuce connection factory with connection pooling.
     * Pool keeps connections warm so each request doesn't pay TCP handshake cost.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig =
                new RedisStandaloneConfiguration(redisHost, redisPort);

        LettucePoolingClientConfiguration poolConfig =
                LettucePoolingClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(2000))
                        .build();

        return new LettuceConnectionFactory(serverConfig, poolConfig);
    }

    /**
     * RedisTemplate typed to <String, String>.
     *
     * WHY StringRedisSerializer:
     * By default, Spring uses Java serialization for keys and values —
     * this adds binary prefixes that make Redis keys unreadable in Redis CLI.
     * StringRedisSerializer stores keys as plain strings, so you can inspect
     * them directly: redis-cli HGETALL "rl:user:u123:search"
     *
     * INTERVIEW POINT:
     * "Why String serializer instead of JSON?"
     * Our Lua script reads raw strings from Redis. If we used Jackson JSON
     * serializer, the stored values would have JSON wrapping that breaks
     * the Lua tonumber() calls. Plain strings are the correct choice here.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(
            RedisConnectionFactory redisConnectionFactory
    ) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();

        return template;
    }

    /**
     * Loads the Lua script from classpath at application startup.
     *
     * DefaultRedisScript works as follows:
     * - First execution: sends full script via EVAL, Redis executes + caches it
     * - Subsequent executions: sends only SHA1 via EVALSHA (40 chars vs ~500)
     * - If Redis is restarted (script cache cleared): automatically falls back to EVAL
     *
     * Return type List.class because our Lua script returns a Redis array:
     * {allowed(0/1), remainingTokens, msUntilNextToken}
     *
     * INTERVIEW POINT:
     * "What does the Lua script return and how do you parse it?"
     * Redis returns Lua arrays as Java List<Object> where each element is a Long.
     * We cast result.get(0) to Long (1=allowed, 0=denied),
     * result.get(1) to Long (remaining tokens),
     * result.get(2) to Long (ms until next token).
     */
    @Bean
    public DefaultRedisScript<List> rateLimiterScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/rate_limiter.lua"));
        script.setResultType(List.class);
        return script;
    }

}