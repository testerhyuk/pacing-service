local result = {}

for index = 1, #KEYS do
    local values = redis.call(
        'HMGET',
        KEYS[index],
        'decisionCount',
        'passCount',
        'reservationCount',
        'reservedAmount'
    )

    table.insert(result, values[1] or '0')
    table.insert(result, values[2] or '0')
    table.insert(result, values[3] or '0')
    table.insert(result, values[4] or '0')
end

return result
