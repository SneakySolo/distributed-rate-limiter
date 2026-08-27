-- Token Bucket Rate Limiter - Atomic Lua Script
--
-- KEYS[1] = Rate limit key (e.g., "ratelimit:user-1:otp")
-- ARGV[1] = Current timestamp (milliseconds)
-- ARGV[2] = Bucket capacity (e.g., 100)
-- ARGV[3] = Refill rate per minute (e.g., 100)
-- ARGV[4] = TTL in milliseconds (e.g., 300000 for 5 minutes)
--
-- RETURNS:
-- {allowed, remainingTokens, retryAfterMs}
-- - allowed: 1 if allowed, 0 if rejected
-- - remainingTokens: tokens left after consumption (or 0 if rejected)
-- - retryAfterMs: milliseconds to retry (or 0 if allowed)

local key = KEYS[1]
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refillRate = tonumber(ARGV[3])  -- tokens per minute
local ttlMs = tonumber(ARGV[4])

-- STEP 1: Get current bucket state from Redis
-- We store state as JSON string: {"tokens":50,"lastRefillMs":1000}
local stateJson = redis.call('GET', key)
local state

if stateJson == false then
    -- First request: initialize bucket
    state = {
        tokens = capacity,
        lastRefillMs = now
    }
    redis.call('SET', key, tostring(capacity) .. '|' .. tostring(now))
    redis.call('EXPIRE', key, math.ceil(ttlMs / 1000))
else
    -- Parse existing state (format: "tokens|lastRefillMs")
    local parts = {}
    for part in stateJson:gmatch('[^|]+') do
        table.insert(parts, tonumber(part))
    end
    state = {
        tokens = parts[1],
        lastRefillMs = parts[2]
    }
end

-- STEP 2: Calculate refill
local elapsedMs = now - state.lastRefillMs
local tokensToAdd = math.floor((refillRate * elapsedMs) / 60000)  -- Integer division

-- STEP 3: Refill tokens (capped at capacity)
if tokensToAdd > 0 then
    state.tokens = math.min(capacity, state.tokens + tokensToAdd)
    state.lastRefillMs = now
end

-- STEP 4: Attempt consumption
local allowed = 0
local remainingTokens = 0
local retryAfterMs = 0

if state.tokens > 0 then
    -- ALLOWED: consume one token
    state.tokens = state.tokens - 1
    allowed = 1
    remainingTokens = state.tokens
else
    -- REJECTED: no tokens
    allowed = 0
    remainingTokens = 0
    retryAfterMs = math.floor(60000 / refillRate)  -- One token refill time
end

-- STEP 5: Update Redis state (atomically with this script)
redis.call('SET', key, tostring(state.tokens) .. '|' .. tostring(state.lastRefillMs))
redis.call('EXPIRE', key, math.ceil(ttlMs / 1000))

-- STEP 6: Return result
return {allowed, remainingTokens, retryAfterMs}