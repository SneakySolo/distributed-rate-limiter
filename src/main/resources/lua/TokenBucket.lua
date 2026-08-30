-- Token Bucket Atomic Operation
-- KEYS[1] = bucket key (e.g., "tb:user-123:otp")
-- ARGV[1] = capacity
-- ARGV[2] = refill rate per minute (tokens)
-- ARGV[3] = ttl seconds
-- ARGV[4] = current Redis time in milliseconds
--
-- Returns: {allowed (1 or 0), remainingTokens, retryAfterMs}

local bucketKey = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRatePerMin = tonumber(ARGV[2])
local ttlSecs = tonumber(ARGV[3])
local nowMs = tonumber(ARGV[4])

-- Refill rate in tokens per millisecond
local refillRatePerMs = refillRatePerMin / 60000.0

-- Fetch current bucket state
local bucket = redis.call('HGETALL', bucketKey)
local tokens = capacity
local lastRefillMs = nowMs

if #bucket > 0 then
    tokens = tonumber(bucket[2]) or capacity
    lastRefillMs = tonumber(bucket[4]) or nowMs
end

-- Calculate elapsed time since last refill
local elapsedMs = nowMs - lastRefillMs

-- Add refilled tokens
local refilled = math.floor(elapsedMs * refillRatePerMs)
if refilled > 0 then
    tokens = math.min(tokens + refilled, capacity)
    lastRefillMs = nowMs
end

-- Try to consume one token
local allowed = 0
local remaining = tokens
local retryAfterMs = 0

if tokens > 0 then
    allowed = 1
    tokens = tokens - 1
    remaining = tokens
else
    -- Calculate when next token will be available
    retryAfterMs = math.ceil(1000.0 / refillRatePerMs)
end

-- Store updated bucket state
redis.call('HSET', bucketKey, 'tokens', remaining, 'lastRefillMs', lastRefillMs)
redis.call('EXPIRE', bucketKey, ttlSecs)

return {allowed, remaining, retryAfterMs}