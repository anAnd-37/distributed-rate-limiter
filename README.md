# Distributed API Rate Limiter

A production-grade distributed rate limiter built from scratch — no off-the-shelf
libraries. Solves the distributed coordination problem of maintaining accurate,
atomic request counters across multiple app servers.

## The Core Problem
In a single-server world, rate limiting is trivial. In a distributed system with
10+ app servers, each server can't keep its own local counter — they'd all count
independently and allow 10x the intended rate. You need a shared, atomic counter.

## Solution — Redis + Lua
The entire token bucket read-modify-write executes as one atomic Redis operation
via a Lua script. Redis is single-threaded — while Lua executes, nothing else runs.
Zero race conditions. No locks. No retries.

## Architecture

Request → Load Balancer → App Server (Spring Boot)
↓
AOP Interceptor (@RateLimit)
↓
RateLimiterService
↓
Redis Lua Script (atomic)
↓
ALLOWED → proceed
DENIED  → HTTP 429 + Retry-After

## Key Design Decisions
| Decision | Choice | Rejected Alternative | Why |
|---|---|---|---|
| Algorithm | Token Bucket | Sliding Window Log | O(1) memory vs O(n) per client |
| Atomicity | Lua script | MULTI/EXEC transactions | No retries, predictable latency |
| Interception | Spring AOP | Servlet Filter | Method-level granularity + Security context |
| Redis driver | Lettuce | Jedis | Async, thread-safe, one pool for all threads |
| Fallback | Configurable ALLOW/DENY | Hard fail | Different endpoints need different safety tradeoffs |

## Tech Stack
- **Java 23** + **Spring Boot 3.5**
- **Redis 7** — atomic Lua scripting, token bucket state
- **Spring AOP** — declarative `@RateLimit` annotation
- **Micrometer + Prometheus + Grafana** — quota metrics and alerting
- **Docker Compose** — full local infra in one command

## Running Locally
```bash
# Start Redis + Prometheus + Grafana
docker-compose up -d

# Start the application
mvn spring-boot:run

# Test endpoints
curl -u admin:admin123 http://localhost:8080/auth/endpoints
```

## API Endpoints
| Endpoint | Limit | Key Type | Fallback |
|---|---|---|---|
| GET /api/search | 10 req / 60s | USER | ALLOW |
| GET /api/data | 5 req / 30s | USER | ALLOW |
| POST /api/upload | 3 req / 60s | IP | DENY |
| GET /api/premium | 20 req / 60s | COMPOSITE | DENY |
| POST /auth/token | 5 req / 60s | IP | DENY |

## Rate Limit Response Headers

X-RateLimit-Limit     : 10
X-RateLimit-Remaining : 0
X-RateLimit-Reset     : 1779223440
Retry-After           : 4

## Observable via Prometheus

rate_limiter_requests_total{decision="allowed"}
rate_limiter_requests_total{decision="denied"}
rate_limiter_requests_total{decision="fallback"}

## Performance
- Rate limiter overhead: **p99 under 6ms**
- Redis Lua script: **atomic, zero retries**
- Lettuce connection pool: **10 concurrent Redis connections**
- Cold start Redis call: ~1100ms, subsequent calls: **2-6ms**
