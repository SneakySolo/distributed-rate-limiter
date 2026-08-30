-- Leaky Bucket Enqueue Atomic Operation
-- KEYS[1] = queue key (e.g., "lb:user-123:payment:queue")
-- ARGV[1] = request id
-- ARGV[2] = scheduled time in milliseconds
-- ARGV[3] = queue capacity
-- ARGV[4] = current Redis time in milliseconds
--
-- Returns: {success (1 or 0), queueDepth}

local queueKey = KEYS[1]
local requestId = ARGV[1]
local scheduledMs = tonumber(ARGV[2])
local capacity = tonumber(ARGV[3])
local nowMs = tonumber(ARGV[4])

-- Check current queue depth
local queueDepth = redis.call('ZCARD', queueKey)

if queueDepth >= capacity then
    return {0, queueDepth}
end

-- Add to sorted set with scheduled time as score
redis.call('ZADD', queueKey, scheduledMs, requestId)
queueDepth = redis.call('ZCARD', queueKey)

return {1, queueDepth}