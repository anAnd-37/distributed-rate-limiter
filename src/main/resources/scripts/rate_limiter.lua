-- Distributed Token Bucket Rate Limiter
-- Executes atomically inside Redis — no race conditions possible
--
-- KEYS[1]  : Redis key (e.g. "rl:user:u123:search")
-- ARGV[1]  : max tokens (bucket capacity)
-- ARGV[2]  : refill rate (tokens per second)
-- ARGV[3]  : current timestamp in milliseconds
-- ARGV[4]  : TTL in seconds (how long to keep the key alive)
--
-- RETURNS  : List of 3 values
--   [1] = 1 (allowed) or 0 (denied)
--   [2] = remaining tokens after this request
--   [3] = milliseconds until next token is available (0 if allowed)

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refill_rate    = tonumber(ARGV[2])   -- tokens added per second
local now            = tonumber(ARGV[3])   -- current time in ms
local ttl            = tonumber(ARGV[4])   -- key expiry in seconds

-- Read current state from Redis hash
local data           = redis.call('HMGET', key, 'tokens', 'last_refill')
local stored_tokens  = tonumber(data[1])
local last_refill    = tonumber(data[2])

-- First request for this key — initialize bucket to full capacity
if stored_tokens == nil or last_refill == nil then
	stored_tokens = capacity
	last_refill   = now
end

-- Calculate how many tokens to add since the last request
-- elapsed is in milliseconds, refill_rate is tokens/sec, so divide by 1000
local elapsed_ms     = math.max(0, now - last_refill)
local tokens_to_add  = (elapsed_ms / 1000) * refill_rate

-- Refill the bucket, but never exceed capacity (clamp to max)
local new_tokens     = math.min(capacity, stored_tokens + tokens_to_add)

-- Not enough tokens — request DENIED
if new_tokens < 1 then
-- Calculate exactly when the next token will be available
	local ms_until_next_token = math.ceil((1 - new_tokens) / refill_rate * 1000)

	-- Still update last_refill so partial refills are tracked correctly
	redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
	redis.call('EXPIRE', key, ttl)

	return {0, 0, ms_until_next_token}
end

-- Enough tokens — request ALLOWED, consume 1 token
local remaining = new_tokens - 1

redis.call('HMSET', key,
	'tokens',      remaining,
	'last_refill', now
)
redis.call('EXPIRE', key, ttl)

return {1, math.floor(remaining), 0}