[CmdletBinding()]
param(
    [int]$SuccessfulReservations = 500,

    [int]$Attempts = 1000,

    [long]$Amount = 100,

    [int]$VUs = 400,

    [int]$TimeoutSeconds = 300,

    [switch]$SkipStart,

    [switch]$NoBuild,

    [switch]$Monitoring,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($SuccessfulReservations -le 0) {
    throw "SuccessfulReservations must be greater than 0."
}
if ($Attempts -le $SuccessfulReservations) {
    throw "Attempts must be greater than SuccessfulReservations."
}
if ($Amount -le 1) {
    throw "Amount must be greater than 1."
}
if ($VUs -le 0 -or $VUs -gt $Attempts) {
    throw "VUs must be between 1 and Attempts."
}
if ($TimeoutSeconds -le 0) {
    throw "TimeoutSeconds must be greater than 0."
}

$expectedReservedAmount = (
    [long]$SuccessfulReservations *
    $Amount
)
$unusableRemainder = $Amount - 1L
$boundaryBudget = (
    $expectedReservedAmount +
    $unusableRemainder
)

if (
    $expectedReservedAmount -le 0 -or
    $boundaryBudget -le $expectedReservedAmount
) {
    throw "Calculated budget values are invalid or overflowed."
}

$runId = New-TestIdentifier -Prefix "budget-boundary"
$reportDirectory = Join-Path `
    (Get-ProjectRoot) `
    "build\reports\budget-boundary\$runId"
$loadScriptDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "load")
)
$loadScript = Join-Path `
    $loadScriptDirectory `
    "budget-boundary.js"
$k6Image = Get-EnvironmentValue `
    -Name "PACING_K6_IMAGE" `
    -DefaultValue "grafana/k6:latest"

[void](New-Item `
    -ItemType Directory `
    -Path $reportDirectory `
    -Force)

if (-not (Test-Path -LiteralPath $loadScript)) {
    throw "Missing file: $loadScript"
}

function Convert-ToLongValue {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is empty."
    }

    [long]$parsed = 0
    if (-not [long]::TryParse($Value.Trim(), [ref]$parsed)) {
        throw "$Name is not a long value: $Value"
    }

    return $parsed
}

function Get-DbLong {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $raw = Invoke-PostgresScalar -Sql $Sql
    return Convert-ToLongValue -Value $raw -Name $Name
}

function Get-RedisBudgetLong {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Key,

        [Parameter(Mandatory = $true)]
        [string]$Field
    )

    $raw = Invoke-RedisScalar `
        -RedisArguments @("HGET", $Key, $Field)

    return Convert-ToLongValue `
        -Value $raw `
        -Name "Redis $Key/$Field"
}

function Get-ApplicationDockerNetwork {
    $raw = Invoke-DockerCommand -Arguments @(
        "inspect",
        "pacing-api",
        "--format",
        "{{json .NetworkSettings.Networks}}"
    )
    $jsonText = (
        $raw |
            ForEach-Object { [string]$_ }
    ) -join ""

    if ([string]::IsNullOrWhiteSpace($jsonText)) {
        throw "Could not read pacing-api Docker networks."
    }

    $networkObject = $jsonText | ConvertFrom-Json
    $networkNames = @(
        $networkObject.PSObject.Properties |
            ForEach-Object { $_.Name }
    )

    if ($networkNames.Count -eq 0) {
        throw "pacing-api is not connected to a Docker network."
    }

    return [string]$networkNames[0]
}

function Get-K6Counter {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary,

        [Parameter(Mandatory = $true)]
        [string]$MetricName
    )

    $metricProperty = $Summary.metrics.PSObject.Properties[
        $MetricName
    ]

    if ($null -eq $metricProperty) {
        throw "k6 metric is missing: $MetricName"
    }

    $metric = $metricProperty.Value
    $countProperty = $metric.PSObject.Properties["count"]

    # Current k6 summary-export writes Counter.count directly.
    if ($null -ne $countProperty) {
        return [long]$countProperty.Value
    }

    # Keep compatibility with summary formats that nest values.
    $valuesProperty = $metric.PSObject.Properties["values"]
    if ($null -ne $valuesProperty) {
        $nestedCountProperty =
            $valuesProperty.Value.PSObject.Properties["count"]

        if ($null -ne $nestedCountProperty) {
            return [long]$nestedCountProperty.Value
        }
    }

    throw "k6 counter value is missing: $MetricName"
}

function Invoke-BoundaryCase {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CaseName,

        [Parameter(Mandatory = $true)]
        [long]$TotalBudget,

        [Parameter(Mandatory = $true)]
        [long]$DailyBudgetLimit,

        [Parameter(Mandatory = $true)]
        [string]$DockerNetwork
    )

    $campaignId = New-TestIdentifier `
        -Prefix "boundary-$CaseName-campaign"
    $reservationPrefix = New-TestIdentifier `
        -Prefix "boundary-$CaseName-reservation"
    $summaryFileName = "$CaseName-summary.json"
    $summaryFile = Join-Path `
        $reportDirectory `
        $summaryFileName
    $now = [DateTimeOffset]::UtcNow

    Write-Step (
        "Boundary case: $CaseName " +
        "(total=$TotalBudget, daily=$DailyBudgetLimit)"
    )

    $campaignParameters = @{
        CampaignId = $campaignId
        Status = "ACTIVE"
        PacingStrategy = "ASAP"
        TotalBudget = $TotalBudget
        DailyBudgetLimit = $DailyBudgetLimit
        StartAt = $now.AddMinutes(-5)
        EndAt = $now.AddDays(1)
        PassThru = $true
        EnvironmentFile = $EnvironmentFile
    }
    $campaignResponse = & (
        Join-Path $PSScriptRoot "upsert-campaign.ps1"
    ) @campaignParameters

    Assert-Equal `
        -Expected $campaignId `
        -Actual ([string]$campaignResponse.Json.campaignId) `
        -Message "Campaign id mismatch."

    $initialDecision = & (
        Join-Path $PSScriptRoot "request-pacing-decision.ps1"
    ) `
        -CampaignId $campaignId `
        -RequestId (
            New-TestIdentifier -Prefix "boundary-init"
        ) `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected "PASS" `
        -Actual ([string]$initialDecision.Json.decision) `
        -Message "Initial decision was not PASS."

    $adServerSecret = Get-HmacSecret `
        -ClientId "ad-server"
    $auctionServerSecret = Get-HmacSecret `
        -ClientId "auction-server"
    $k6Arguments = @(
        "run",
        "--rm",
        "--network",
        $DockerNetwork,
        "-e",
        "BASE_URL=http://pacing-api:8080",
        "-e",
        "CAMPAIGN_ID=$campaignId",
        "-e",
        "RESERVATION_PREFIX=$reservationPrefix",
        "-e",
        "AD_SERVER_SECRET=$adServerSecret",
        "-e",
        "AUCTION_SERVER_SECRET=$auctionServerSecret",
        "-e",
        "AMOUNT=$Amount",
        "-e",
        "ATTEMPTS=$Attempts",
        "-e",
        "EXPECTED_CREATED=$SuccessfulReservations",
        "-e",
        "VUS=$VUs",
        "-v",
        "${loadScriptDirectory}:/scripts:ro",
        "-v",
        "${reportDirectory}:/results",
        $k6Image,
        "run",
        "--summary-export",
        "/results/$summaryFileName",
        "/scripts/budget-boundary.js"
    )

    [void](Invoke-DockerCommand `
        -Arguments $k6Arguments `
        -StreamOutput)

    if (-not (Test-Path -LiteralPath $summaryFile)) {
        throw "k6 summary was not created: $summaryFile"
    }

    $summary = Get-Content `
        -LiteralPath $summaryFile `
        -Raw |
        ConvertFrom-Json
    $createdCount = Get-K6Counter `
        -Summary $summary `
        -MetricName "boundary_reservation_created"
    $insufficientCount = Get-K6Counter `
        -Summary $summary `
        -MetricName "boundary_reservation_insufficient"
    $unexpectedCount = Get-K6Counter `
        -Summary $summary `
        -MetricName "boundary_reservation_unexpected"

    Assert-Equal `
        -Expected ([long]$SuccessfulReservations) `
        -Actual $createdCount `
        -Message "Created response count mismatch."
    Assert-Equal `
        -Expected ([long](
            $Attempts -
            $SuccessfulReservations
        )) `
        -Actual $insufficientCount `
        -Message "Insufficient response count mismatch."
    Assert-Equal `
        -Expected 0L `
        -Actual $unexpectedCount `
        -Message "Unexpected reservation responses exist."

    $campaignLiteral = ConvertTo-SqlLiteral `
        -Value $campaignId
    $reservationCount = Get-DbLong `
        -Sql (
            "SELECT COUNT(*) FROM budget_reservation " +
            "WHERE campaign_id = $campaignLiteral"
        ) `
        -Name "$CaseName reservation count"
    $reservedCount = Get-DbLong `
        -Sql (
            "SELECT COUNT(*) FROM budget_reservation " +
            "WHERE campaign_id = $campaignLiteral " +
            "AND status = 'RESERVED'"
        ) `
        -Name "$CaseName reserved count"
    $reservedAmount = Get-DbLong `
        -Sql (
            "SELECT COALESCE(SUM(amount), 0) " +
            "FROM budget_reservation " +
            "WHERE campaign_id = $campaignLiteral " +
            "AND status = 'RESERVED'"
        ) `
        -Name "$CaseName reserved amount"
    $budgetDate = Invoke-PostgresScalar -Sql (
        "SELECT MIN(budget_date)::text " +
        "FROM budget_reservation " +
        "WHERE campaign_id = $campaignLiteral"
    )

    Assert-Equal `
        -Expected ([long]$SuccessfulReservations) `
        -Actual $reservationCount `
        -Message "Persisted reservation count mismatch."
    Assert-Equal `
        -Expected ([long]$SuccessfulReservations) `
        -Actual $reservedCount `
        -Message "RESERVED row count mismatch."
    Assert-Equal `
        -Expected $expectedReservedAmount `
        -Actual $reservedAmount `
        -Message "PostgreSQL reserved amount mismatch."

    if ([string]::IsNullOrWhiteSpace($budgetDate)) {
        throw "Could not read reservation budget_date."
    }

    $encodedCampaignId = ConvertTo-RedisKeyPart `
        -Value $campaignId
    $totalBudgetKey = (
        "pacing:budget:total:{$encodedCampaignId}"
    )
    $dailyBudgetKey = (
        "pacing:budget:daily:{$encodedCampaignId}:" +
        $budgetDate
    )
    $redisTotalBudget = Get-RedisBudgetLong `
        -Key $totalBudgetKey `
        -Field "totalBudget"
    $redisTotalSpent = Get-RedisBudgetLong `
        -Key $totalBudgetKey `
        -Field "totalSpentAmount"
    $redisTotalReserved = Get-RedisBudgetLong `
        -Key $totalBudgetKey `
        -Field "totalReservedAmount"
    $redisDailyLimit = Get-RedisBudgetLong `
        -Key $dailyBudgetKey `
        -Field "dailyBudgetLimit"
    $redisDailySpent = Get-RedisBudgetLong `
        -Key $dailyBudgetKey `
        -Field "dailySpentAmount"
    $redisDailyReserved = Get-RedisBudgetLong `
        -Key $dailyBudgetKey `
        -Field "dailyReservedAmount"

    Assert-Equal `
        -Expected $TotalBudget `
        -Actual $redisTotalBudget `
        -Message "Redis total budget mismatch."
    Assert-Equal `
        -Expected 0L `
        -Actual $redisTotalSpent `
        -Message "Redis total spent must be 0."
    Assert-Equal `
        -Expected $expectedReservedAmount `
        -Actual $redisTotalReserved `
        -Message "Redis total reserved mismatch."
    Assert-Equal `
        -Expected $DailyBudgetLimit `
        -Actual $redisDailyLimit `
        -Message "Redis daily limit mismatch."
    Assert-Equal `
        -Expected 0L `
        -Actual $redisDailySpent `
        -Message "Redis daily spent must be 0."
    Assert-Equal `
        -Expected $expectedReservedAmount `
        -Actual $redisDailyReserved `
        -Message "Redis daily reserved mismatch."

    $totalAvailable = (
        $redisTotalBudget -
        $redisTotalSpent -
        $redisTotalReserved
    )
    $dailyAvailable = (
        $redisDailyLimit -
        $redisDailySpent -
        $redisDailyReserved
    )

    if ($totalAvailable -lt 0 -or $dailyAvailable -lt 0) {
        throw (
            "Budget overrun detected: " +
            "totalAvailable=$totalAvailable, " +
            "dailyAvailable=$dailyAvailable"
        )
    }

    Write-Host ""
    Write-Host "Case SUCCESS: $CaseName" `
        -ForegroundColor Green
    Write-Host "campaign:             $campaignId"
    Write-Host "attempts:             $Attempts"
    Write-Host "created:              $createdCount"
    Write-Host "insufficient:         $insufficientCount"
    Write-Host "reserved amount:      $reservedAmount"
    Write-Host "total available:      $totalAvailable"
    Write-Host "daily available:      $dailyAvailable"
    Write-Host "summary:              $summaryFile"
}

Write-Host ""
Write-Host "============================================"
Write-Host " Concurrent Budget Boundary Test"
Write-Host "============================================"
Write-Host "successful reservations: $SuccessfulReservations"
Write-Host "attempts:                $Attempts"
Write-Host "concurrent VUs:          $VUs"
Write-Host "amount per reservation:  $Amount"
Write-Host "reserved amount:         $expectedReservedAmount"
Write-Host "boundary budget:         $boundaryBudget"
Write-Host "unusable remainder:      $unusableRemainder"
Write-Host "report directory:        $reportDirectory"

Write-Step "0. Prepare stack"

if (-not $SkipStart) {
    & (Join-Path $PSScriptRoot "start-local.ps1") `
        -NoBuild:$NoBuild `
        -Monitoring:$Monitoring `
        -TimeoutSeconds $TimeoutSeconds `
        -EnvironmentFile $EnvironmentFile
}
else {
    & (Join-Path $PSScriptRoot "wait-for-services.ps1") `
        -TimeoutSeconds $TimeoutSeconds `
        -EnvironmentFile $EnvironmentFile
}

$dockerNetwork = Get-ApplicationDockerNetwork
Write-Host "Docker network: $dockerNetwork"

Invoke-BoundaryCase `
    -CaseName "total-and-daily" `
    -TotalBudget $boundaryBudget `
    -DailyBudgetLimit $boundaryBudget `
    -DockerNetwork $dockerNetwork

# Allow both client token buckets to refill before the second burst.
Start-Sleep -Seconds 2

Invoke-BoundaryCase `
    -CaseName "daily-only" `
    -TotalBudget ($boundaryBudget * 2L) `
    -DailyBudgetLimit $boundaryBudget `
    -DockerNetwork $dockerNetwork

Write-Host ""
Write-Host "Concurrent Budget Boundary Test SUCCESS" `
    -ForegroundColor Green
Write-Host "Reports: $reportDirectory"
