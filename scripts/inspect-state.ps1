[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CampaignId,

    [string]$ReservationId,

    [string]$BudgetDate,

    [switch]$PassThru,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ([string]::IsNullOrWhiteSpace($BudgetDate)) {
    $seoulNow = [DateTimeOffset]::UtcNow.ToOffset(
        [TimeSpan]::FromHours(9)
    )
    $BudgetDate = $seoulNow.ToString("yyyy-MM-dd")
}

$campaignLiteral = ConvertTo-SqlLiteral -Value $CampaignId
$encodedCampaignId = ConvertTo-RedisKeyPart -Value $CampaignId
$totalKey = "pacing:budget:total:{$encodedCampaignId}"
$dailyKey = "pacing:budget:daily:{$encodedCampaignId}:$BudgetDate"

$campaignRow = Invoke-PostgresScalar -Sql (
    "SELECT status || '|' || pacing_strategy || '|' || " +
    "total_budget || '|' || daily_budget_limit " +
    "FROM campaign WHERE campaign_id = $campaignLiteral"
)

$state = [ordered]@{
    campaignId = $CampaignId
    budgetDate = $BudgetDate
    campaign = $campaignRow
    totalBudget = Invoke-RedisScalar -RedisArguments @(
        "HGET",
        $totalKey,
        "totalBudget"
    )
    totalSpentAmount = Invoke-RedisScalar -RedisArguments @(
        "HGET",
        $totalKey,
        "totalSpentAmount"
    )
    totalReservedAmount = Invoke-RedisScalar -RedisArguments @(
        "HGET",
        $totalKey,
        "totalReservedAmount"
    )
    dailyBudgetLimit = Invoke-RedisScalar -RedisArguments @(
        "HGET",
        $dailyKey,
        "dailyBudgetLimit"
    )
    dailySpentAmount = Invoke-RedisScalar -RedisArguments @(
        "HGET",
        $dailyKey,
        "dailySpentAmount"
    )
    dailyReservedAmount = Invoke-RedisScalar -RedisArguments @(
        "HGET",
        $dailyKey,
        "dailyReservedAmount"
    )
}

if (-not [string]::IsNullOrWhiteSpace($ReservationId)) {
    $reservationLiteral = ConvertTo-SqlLiteral `
        -Value $ReservationId
    $reservationKeyPart = ConvertTo-RedisKeyPart `
        -Value $ReservationId
    $reservationKey = (
        "pacing:reservation:{$encodedCampaignId}:" +
        $reservationKeyPart
    )

    $state.reservationId = $ReservationId
    $state.postgresReservation = Invoke-PostgresScalar -Sql (
        "SELECT status || '|' || amount || '|' || applied_amount " +
        "FROM budget_reservation " +
        "WHERE reservation_id = $reservationLiteral"
    )
    $state.redisReservationStatus = Invoke-RedisScalar `
        -RedisArguments @(
            "HGET",
            $reservationKey,
            "status"
        )
    $state.redisAppliedAmount = Invoke-RedisScalar `
        -RedisArguments @(
            "HGET",
            $reservationKey,
            "appliedAmount"
        )
}

$result = [pscustomobject]$state
Write-Host ($result | ConvertTo-Json -Depth 10)

if ($PassThru) {
    return $result
}
