-- Token Bucket Refill + Consume Script (Phase 2)
--
-- PURPOSE:
-- ========
-- Atomically refill tokens based on elapsed time, then consume one token.
-- Entire operation runs in single Redis command (no race conditions).
--
-- ARGUMENTS:
-- ==========
-- KEYS[1] = Rate limit state key (e.g., "rl:token-bucket:user123:otp")
-- ARGV[1] = Capacity (max tokens, e.g., 100)
-- ARGV[2] = Refill rate per minute (e.g., 100)
-- ARGV[3] = Current timestamp in milliseconds (e.g., 1724893201234)
--
-- RETURN VALUE:
-- ==============
-- {
--   "allowed" or "rejected",    -- [1] Decision
--   remainingTokens,            -- [2] Tokens left (if allowed) or 0 (if rejected)
--   reason,                     -- [3] Reason string
--   retryAfterMs                -- [4] Retry-after in ms (0 if allowed)
-- }
--
-- ATOMICITY GUARANTEE:
-- ====================
-- Redis is single-threaded. This entire script completes before next request.
-- No two requests can see intermediate state.
--
-- EXAMPLE FLOW:
-- =============
-- Request 1: tokens=100, checks rate limit
--   - Elapsed = 600ms → refill 1 token → tokens=100 (capped)
--   - Consume 1 → tokens=99
--   - Return: ALLOWED, 99, "token_available", 0
--
-- Request 2 (immediate): tokens=99
--   - Elapsed = 1ms → refill 0 tokens
--   - Consume 1 → tokens=98
--   - Return: ALLOWED, 98, "token_available", 0
--
-- Request 101 (after token exhaustion):
--   - Elapsed = 1000ms → refill 1.66 ≈ 1 token
--   - Consume 1 → tokens=0
--   - Return: ALLOWED, 0, "token_available", 0
--
-- Request 102 (immediately after):
--   - Elapsed = 1ms → refill 0 tokens
--   - tokens=0, can't consume
--   - Return: REJECTED, 0, "no_tokens_available", 600
--
-- PERFORMANCE:
-- =============
-- Complexity: O(1) - single Redis string read/write
-- Latency: ~1ms (Redis EVAL on localhost)
-- Compared to: in-memory check would be 0.1μs, but not distributed
--
-- THREAD SAFETY:
-- ==============
-- Script is atomic on Redis side. Java doesn't need locks.
-- All three application instances share same Redis keys.
-- No duplicate checks needed.

-- Step 1: Read current state from Redis (or get nil if first request)
local stateJson = redis.call('GET', KEYS[1])

-- Step 2: Parse state or initialize fresh
local capacity = tonumber(ARGV[1])
local refillRatePerMinute = tonumber(ARGV[2])
local nowMs = tonumber(ARGV[3])

local currentTokens
local lastRefillTimeMs

if stateJson == false then
    -- First request for this rate limit
    currentTokens = capacity
    lastRefillTimeMs = nowMs
    -- (state not in Redis yet, will store after consumption)
else
    -- State exists in Redis, parse it
    -- (In production, use cjson library: local cjson = require "cjson")
    -- For Phase 2, we'll manually parse the JSON string
    -- Format expected: {"tokens":95,"lastRefillTimeMs":1724893201000}

    -- Simple manual parsing (works for this specific format)
    -- Extract "tokens":NN
    local tokensMatch = stateJson:match('"tokens":(%d+)')
    currentTokens = tonumber(tokensMatch) or capacity

    -- Extract "lastRefillTimeMs":NNNN
    local lastTimeMatch = stateJson:match('"lastRefillTimeMs":(%d+)')
    lastRefillTimeMs = tonumber(lastTimeMatch) or nowMs
end

-- Step 3: Calculate refill based on elapsed time
local elapsedMs = nowMs - lastRefillTimeMs
local tokensToAdd = 0

if elapsedMs > 0 then
    -- tokensToAdd = (refillRatePerMinute / 60,000) * elapsedMs
    -- In Lua: integer division
    tokensToAdd = (refillRatePerMinute * elapsedMs) / 60000
end

-- Step 4: Update tokens (add refilled, cap at capacity)
currentTokens = math.min(capacity, currentTokens + tokensToAdd)

-- Step 5: Attempt consumption
local retryAfterMs = 0
local allowed = false
local reason = ""

if currentTokens > 0 then
    -- Consume one token
    currentTokens = currentTokens - 1
    allowed = true
    reason = "token_available"
else
    -- No tokens available
    allowed = false
    reason = "no_tokens_available"
    -- Retry after 1 token refill time: 60,000 / refillRatePerMinute
    retryAfterMs = math.floor(60000 / refillRatePerMinute)
end

-- Step 6: Persist updated state to Redis
-- Format: {"tokens":NN,"lastRefillTimeMs":NNNNN}
-- (Using simple string concatenation since Lua numbers preserve precision)
local updatedStateJson = '{"tokens":' .. tostring(math.floor(currentTokens)) ..
                         ',"lastRefillTimeMs":' .. tostring(nowMs) .. '}'

redis.call('SET', KEYS[1], updatedStateJson)

-- Step 7: Return decision to Java
local decision = allowed and "allowed" or "rejected"
return {
    decision,
    math.floor(currentTokens),
    reason,
    retryAfterMs
}

-- =============================================================================
-- DETAILED EXPLANATION OF ALGORITHM
-- =============================================================================
--
-- REFILL CALCULATION:
-- ===================
-- Objective: Add tokens to bucket at fixed rate
-- Rate: refillRatePerMinute tokens per 60,000 milliseconds
--
-- Given:
--   - refillRatePerMinute = 100 (100 requests/min allowed)
--   - elapsedMs = 600 (600 milliseconds passed)
--
-- Calculation:
--   tokensToAdd = (100 / 60,000) * 600
--               = 0.00166... * 600
--               = 1.0
--
-- So: after 600ms, add 1 token
--
-- Why this rate?
--   - 100 tokens per 60,000ms = 1 token per 600ms
--   - Can do 100 requests/minute = 1 request per 600ms on average
--   - Burst capacity = 100 (all available upfront)
--
--
-- CAPACITY CAP:
-- =============
-- Line: currentTokens = math.min(capacity, currentTokens + tokensToAdd)
--
-- Why cap?
--   - Bucket has max capacity (100 tokens)
--   - Don't accumulate tokens indefinitely
--   - If no requests for 1 hour, still only 100 available (not 100*60=6000)
--
-- Example:
--   - Capacity: 100
--   - Current: 100 (full)
--   - Refill: 10 tokens would be added
--   - Result: min(100, 110) = 100 (stays full)
--
--
-- CONSUMPTION:
-- ============
-- Only happens if tokens > 0.
-- Always consumes exactly 1 token (not configurable per-request).
-- Each HTTP request = 1 token.
--
--
-- REJECTION RETRY-AFTER:
-- ======================
-- When no tokens available, return "come back in X ms"
-- X = time until next token available = 1 token refill interval
--
-- Calculation:
--   refillInterval = 60,000 / refillRatePerMinute
--   = 60,000 / 100
--   = 600 ms
--
-- Tells client: "Wait 600ms, then try again (1 token will be available)"
--
--
-- STATE PERSISTENCE:
-- ==================
-- After each request, save state to Redis:
--   SET "rl:token-bucket:user123:otp" '{"tokens":95,"lastRefillTimeMs":1724893201234}'
--
-- Why?
--   - State survives app restart
--   - State shared across instances
--   - Next request reads same state
--
--
-- EDGE CASES HANDLED:
-- ===================
--
-- Case 1: Very long idle time (1 month)
--   elapsedMs = 30 * 24 * 60 * 60 * 1000 ms
--   tokensToAdd = (100 / 60,000) * huge = huge
--   Result: capped at 100 (line: math.min(capacity, ...))
--   ✓ Handled
--
-- Case 2: Clock skew (timestamp goes backward)
--   elapsedMs < 0
--   tokensToAdd = 0 (due to refill = (rate * -ve) / divisor)
--   Result: no refill, use current tokens
--   ✓ Handled (conservative)
--
-- Case 3: Multiple servers with one Redis
--   Each server: sends request with current timestamp
--   Redis: only one request processed at a time
--   Result: no race conditions, true 100 requests/min limit
--   ✓ Handled (reason for Lua script)
--
--
-- JSON FORMAT NOTE:
-- =================
-- This Phase 2 uses manual JSON parsing (string matching).
-- For production (Phase 4), use Redis 7.x native JSON support:
--   redis.call('JSON.SET', KEYS[1], '$.tokens', newTokens)
--   redis.call('JSON.GET', KEYS[1])
--
-- For now, simple string format is sufficient and educational.
--
-- =============================================================================