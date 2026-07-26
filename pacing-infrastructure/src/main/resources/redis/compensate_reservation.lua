local function normalize(value)
    if not value or not string.match(value, '^%d+$') then
        return nil
    end

    local normalized = string.gsub(value, '^0+', '')
    if normalized == '' then
        return '0'
    end
    return normalized
end

local function greaterThanOrEqual(left, right)
    left = normalize(left)
    right = normalize(right)

    if not left or not right then
        return false
    end
    if string.len(left) ~= string.len(right) then
        return string.len(left) > string.len(right)
    end
    return left >= right
end

local totalKey = KEYS[1]
local dailyKey = KEYS[2]
local reservationKey = KEYS[3]
local expiryKey = KEYS[4]

if redis.call('EXISTS', reservationKey) == 0 then
    return 0
end

local existing = redis.call(
    'HMGET',
    reservationKey,
    'reservationId',
    'campaignId',
    'amount',
    'status'
)

if existing[1] ~= ARGV[1]
        or existing[2] ~= ARGV[2]
        or normalize(existing[3]) ~= normalize(ARGV[3])
        or existing[4] ~= 'RESERVED' then
    return 0
end

local totalReserved = redis.call(
    'HGET',
    totalKey,
    'totalReservedAmount'
)
local dailyReserved = redis.call(
    'HGET',
    dailyKey,
    'dailyReservedAmount'
)

if not greaterThanOrEqual(totalReserved, ARGV[3])
        or not greaterThanOrEqual(dailyReserved, ARGV[3]) then
    return -1
end

redis.call(
    'HINCRBY',
    totalKey,
    'totalReservedAmount',
    '-' .. normalize(ARGV[3])
)
redis.call('HINCRBY', totalKey, 'version', 1)
redis.call(
    'HINCRBY',
    dailyKey,
    'dailyReservedAmount',
    '-' .. normalize(ARGV[3])
)
redis.call('HINCRBY', dailyKey, 'version', 1)
redis.call('DEL', reservationKey)
redis.call('ZREM', expiryKey, ARGV[1])

return 1
