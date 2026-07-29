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

local function compare(left, right)
    left = normalize(left)
    right = normalize(right)

    if not left or not right then
        return nil
    end

    if string.len(left) < string.len(right) then
        return -1
    end
    if string.len(left) > string.len(right) then
        return 1
    end
    if left < right then
        return -1
    end
    if left > right then
        return 1
    end
    return 0
end

local function add(left, right)
    left = normalize(left)
    right = normalize(right)

    if not left or not right then
        return nil
    end

    local leftIndex = string.len(left)
    local rightIndex = string.len(right)
    local carry = 0
    local result = {}

    while leftIndex > 0 or rightIndex > 0 or carry > 0 do
        local leftDigit = 0
        local rightDigit = 0

        if leftIndex > 0 then
            leftDigit = tonumber(string.sub(left, leftIndex, leftIndex))
            leftIndex = leftIndex - 1
        end

        if rightIndex > 0 then
            rightDigit = tonumber(string.sub(right, rightIndex, rightIndex))
            rightIndex = rightIndex - 1
        end

        local sum = leftDigit + rightDigit + carry
        table.insert(result, 1, tostring(sum % 10))
        carry = math.floor(sum / 10)
    end

    return table.concat(result)
end

local totalKey = KEYS[1]
local dailyKey = KEYS[2]
local reservationKey = KEYS[3]
local expiryKey = KEYS[4]
local persistencePendingKey = KEYS[5]
local campaignPersistencePendingKey = KEYS[6]

local reservationId = ARGV[1]
local campaignId = ARGV[2]
local budgetDate = ARGV[3]
local amount = normalize(ARGV[4])
local reservedAt = ARGV[5]
local expiresAt = ARGV[6]
local persistenceMember = ARGV[7]
local persistencePendingAt = ARGV[8]

if not amount or amount == '0' then
    return {'CONFLICT'}
end

if not persistencePendingAt
        or not string.match(persistencePendingAt, '^%d+$') then
    return {'CONFLICT'}
end

if redis.call('EXISTS', reservationKey) == 1 then
    local existing = redis.call(
        'HMGET',
        reservationKey,
        'reservationId',
        'campaignId',
        'budgetDate',
        'amount',
        'status',
        'reservedAtEpochMillis',
        'expiresAtEpochMillis'
    )

    if existing[2] == campaignId and normalize(existing[4]) == amount then
        return {
            'ALREADY_EXISTS',
            existing[1],
            existing[2],
            existing[3],
            existing[4],
            existing[5],
            existing[6],
            existing[7]
        }
    end

    return {'CONFLICT'}
end

if redis.call('EXISTS', totalKey) == 0
        or redis.call('EXISTS', dailyKey) == 0 then
    return {'BUDGET_STATE_NOT_FOUND'}
end

local total = redis.call(
    'HMGET',
    totalKey,
    'totalBudget',
    'totalSpentAmount',
    'totalReservedAmount'
)
local daily = redis.call(
    'HMGET',
    dailyKey,
    'dailyBudgetLimit',
    'dailySpentAmount',
    'dailyReservedAmount'
)

if not total[1] or not total[2] or not total[3]
        or not daily[1] or not daily[2] or not daily[3] then
    return {'BUDGET_STATE_NOT_FOUND'}
end

local totalAfterReservation = add(add(total[2], total[3]), amount)
local dailyAfterReservation = add(add(daily[2], daily[3]), amount)

if not totalAfterReservation or not dailyAfterReservation then
    return {'CONFLICT'}
end

if compare(totalAfterReservation, total[1]) == 1
        or compare(dailyAfterReservation, daily[1]) == 1 then
    return {'INSUFFICIENT_BUDGET'}
end

redis.call('HINCRBY', totalKey, 'totalReservedAmount', amount)
redis.call('HINCRBY', totalKey, 'version', 1)
redis.call('HINCRBY', dailyKey, 'dailyReservedAmount', amount)
redis.call('HINCRBY', dailyKey, 'version', 1)

redis.call(
    'HSET',
    reservationKey,
    'reservationId', reservationId,
    'campaignId', campaignId,
    'budgetDate', budgetDate,
    'amount', amount,
    'appliedAmount', '0',
    'status', 'RESERVED',
    'reservedAtEpochMillis', reservedAt,
    'expiresAtEpochMillis', expiresAt,
    'version', '0'
)

redis.call(
    'ZADD',
    expiryKey,
    tonumber(expiresAt),
    reservationId
)

redis.call(
    'ZADD',
    persistencePendingKey,
    tonumber(persistencePendingAt),
    persistenceMember
)

redis.call(
    'ZADD',
    campaignPersistencePendingKey,
    tonumber(persistencePendingAt),
    persistenceMember
)

return {
    'CREATED',
    reservationId,
    campaignId,
    budgetDate,
    amount,
    'RESERVED',
    reservedAt,
    expiresAt
}
