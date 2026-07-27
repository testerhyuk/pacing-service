local campaignKey = KEYS[1]
local totalBudgetKey = KEYS[2]
local dailyBudgetKey = KEYS[3]
local pacingStateKey = KEYS[4]

-- Campaign 존재 확인
if redis.call('EXISTS', campaignKey) == 0 then
    return {'MISSING_CAMPAIGN'}
end

-- Total Budget 존재 확인
if redis.call('EXISTS', totalBudgetKey) == 0 then
    return {'MISSING_TOTAL_BUDGET'}
end

-- Daily Budget 존재 확인
if redis.call('EXISTS', dailyBudgetKey) == 0 then
    return {'MISSING_DAILY_BUDGET'}
end

-- Pacing State 존재 확인
if redis.call('EXISTS', pacingStateKey) == 0 then
    return {'MISSING_PACING_STATE'}
end

-- Campaign
local campaignValues = redis.call(
    'HMGET',
    campaignKey,
    'campaignId',
    'status',
    'startAtEpochMillis',
    'endAtEpochMillis',
    'pacingStrategy'
)

if not campaignValues[1]
        or not campaignValues[2]
        or not campaignValues[3]
        or not campaignValues[4]
        or not campaignValues[5] then
    return {'CORRUPTED_CAMPAIGN'}
end

-- Total Budget
local totalBudgetValues = redis.call(
    'HMGET',
    totalBudgetKey,
    'totalBudget',
    'totalSpentAmount',
    'totalReservedAmount',
    'version'
)

if not totalBudgetValues[1]
        or not totalBudgetValues[2]
        or not totalBudgetValues[3]
        or not totalBudgetValues[4] then
    return {'CORRUPTED_TOTAL_BUDGET'}
end

-- Daily Budget
local dailyBudgetValues = redis.call(
    'HMGET',
    dailyBudgetKey,
    'dailyBudgetLimit',
    'dailySpentAmount',
    'dailyReservedAmount',
    'version'
)

if not dailyBudgetValues[1]
        or not dailyBudgetValues[2]
        or not dailyBudgetValues[3]
        or not dailyBudgetValues[4] then
    return {'CORRUPTED_DAILY_BUDGET'}
end

-- Pacing State
local pacingStateValues = redis.call(
    'HMGET',
    pacingStateKey,
    'pacingRate',
    'updatedAtEpochMillis',
    'version'
)

if not pacingStateValues[1]
        or not pacingStateValues[2]
        or not pacingStateValues[3] then
    return {'CORRUPTED_PACING_STATE'}
end

return {
    'FOUND',

    -- Campaign 1 ~ 5
    campaignValues[1],
    campaignValues[2],
    campaignValues[3],
    campaignValues[4],
    campaignValues[5],

    -- Total Budget 6 ~ 9
    totalBudgetValues[1],
    totalBudgetValues[2],
    totalBudgetValues[3],
    totalBudgetValues[4],

    -- Daily Budget 10 ~ 13
    dailyBudgetValues[1],
    dailyBudgetValues[2],
    dailyBudgetValues[3],
    dailyBudgetValues[4],

    -- Pacing State 14 ~ 16
    pacingStateValues[1],
    pacingStateValues[2],
    pacingStateValues[3]
}