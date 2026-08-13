[CmdletBinding()]
param(
    [switch]$SkipStart,

    [switch]$NoBuild,

    [switch]$Monitoring,

    [switch]$Glowroot,

    [int]$TimeoutSeconds = 240,

    [string]$GlowrootHome,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($TimeoutSeconds -le 0) {
    throw "TimeoutSeconds는 0보다 커야 합니다."
}

$campaignId = New-TestIdentifier -Prefix "redis-recovery-campaign"
$confirmedReservationId = New-TestIdentifier -Prefix "redis-recovery-confirmed"
$pendingReservationId = New-TestIdentifier -Prefix "redis-recovery-pending"
$initialChargeEventId = New-TestIdentifier -Prefix "redis-recovery-initial-charge"
$outageChargeEventId = New-TestIdentifier -Prefix "redis-recovery-outage-charge"

$reservationAmount = 1000L
$initialChargeAmount = 900L
$outageChargeAmount = 800L
$expectedFinalSpend = $initialChargeAmount + $outageChargeAmount

$encodedCampaignId = ConvertTo-RedisKeyPart -Value $campaignId
$businessNow = [DateTimeOffset]::UtcNow.ToOffset(
    [TimeSpan]::FromHours(9)
)
$budgetDate = $businessNow.ToString("yyyy-MM-dd")

$campaignKey = "pacing:campaign:{$encodedCampaignId}"
$totalBudgetKey = "pacing:budget:total:{$encodedCampaignId}"
$dailyBudgetKey = (
    "pacing:budget:daily:{$encodedCampaignId}:$budgetDate"
)
$pacingStateKey = "pacing:pacing-state:{$encodedCampaignId}"

$redisStopped = $false

function Get-DbValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    return Invoke-PostgresScalar -Sql $Sql
}

function Get-ReservationState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReservationId
    )

    $literal = ConvertTo-SqlLiteral -Value $ReservationId

    return Get-DbValue -Sql (
        "SELECT status || '|' || amount || '|' || " +
        "applied_amount || '|' || version " +
        "FROM budget_reservation " +
        "WHERE reservation_id = $literal"
    )
}

function Get-BillingStatus {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EventId
    )

    $literal = ConvertTo-SqlLiteral -Value $EventId

    return Get-DbValue -Sql (
        "SELECT processing_status FROM billing_event " +
        "WHERE event_id = $literal"
    )
}

function Wait-ForReservationState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReservationId,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedState
    )

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage (
            "예약 상태 확인 시간 초과: " +
            "reservationId=$ReservationId expected=$ExpectedState"
        ) `
        -Condition {
            return (
                Get-ReservationState -ReservationId $ReservationId
            ) -eq $ExpectedState
        }
}

function Wait-ForBillingStatus {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EventId,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedStatus,

        [int]$WaitTimeoutSeconds = $TimeoutSeconds
    )

    Wait-Until `
        -TimeoutSeconds $WaitTimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage (
            "과금 이벤트 상태 확인 시간 초과: " +
            "eventId=$EventId expected=$ExpectedStatus"
        ) `
        -Condition {
            return (
                Get-BillingStatus -EventId $EventId
            ) -eq $ExpectedStatus
        }
}

function Get-MetricCount {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,

        [Parameter(Mandatory = $true)]
        [string]$MetricName,

        [Parameter(Mandatory = $true)]
        [string]$LabelName,

        [Parameter(Mandatory = $true)]
        [string]$LabelValue
    )

    $response = Invoke-UnsignedRequest -Uri (
        $BaseUrl.TrimEnd("/") + "/actuator/prometheus"
    )

    Assert-HttpStatus `
        -Response $response `
        -ExpectedStatus 200 `
        -Operation "Prometheus 메트릭 조회"

    [double]$total = 0
    $escapedMetricName = [regex]::Escape($MetricName)
    $lines = $response.Body -split "`r?`n"

    foreach ($line in $lines) {
        if ($line -notmatch (
            "^$escapedMetricName" +
            '\{(?<labels>[^}]*)\}\s+' +
            '(?<value>[-+0-9.eE]+)\s*$'
        )) {
            continue
        }

        $labels = [string]$Matches["labels"]
        if (-not $labels.Contains(
            "$LabelName=`"$LabelValue`""
        )) {
            continue
        }

        $total += [double]::Parse(
            $Matches["value"],
            [Globalization.CultureInfo]::InvariantCulture
        )
    }

    return $total
}

function Get-RedisLong {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Key,

        [Parameter(Mandatory = $true)]
        [string]$Field
    )

    $raw = Invoke-RedisScalar -RedisArguments @(
        "HGET",
        $Key,
        $Field
    )

    [long]$value = 0
    if (-not [long]::TryParse($raw, [ref]$value)) {
        throw "Redis 값이 정수가 아닙니다: key=$Key field=$Field value=$raw"
    }

    return $value
}

function Get-CoreRedisKeyCount {
    $raw = Invoke-RedisScalar -RedisArguments @(
        "EXISTS",
        $campaignKey,
        $totalBudgetKey,
        $dailyBudgetKey,
        $pacingStateKey
    )

    [int]$count = 0
    if (-not [int]::TryParse($raw, [ref]$count)) {
        throw "Redis 핵심 Key 개수를 읽을 수 없습니다: $raw"
    }

    return $count
}

function Remove-TestCampaignRedisState {
    $pattern = "pacing:*:{$encodedCampaignId}*"
    $raw = Invoke-RedisScalar -RedisArguments @(
        "KEYS",
        $pattern
    )

    $keys = @(
        $raw -split "`r?`n" |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace($_)
            }
    )

    if ($keys.Count -eq 0) {
        throw "삭제할 테스트 캠페인 Redis Key가 없습니다."
    }

    $deleteArguments = @("DEL") + $keys
    $deleted = Invoke-RedisScalar `
        -RedisArguments $deleteArguments

    Write-Host "삭제한 테스트 캠페인 Redis Key: $deleted"
    Write-Host "Redis Key 패턴: $pattern"
}

function Stop-TestRedis {
    Write-Host "Redis 컨테이너를 중지합니다." -ForegroundColor Yellow

    Push-Location (Get-ProjectRoot)
    try {
        [void](Invoke-DockerCommand `
            -Arguments @(
                "compose",
                "stop",
                "redis"
            ) `
            -StreamOutput)
    }
    finally {
        Pop-Location
    }
}

function Start-TestRedis {
    Write-Host "Redis 컨테이너를 시작합니다." -ForegroundColor Yellow

    Push-Location (Get-ProjectRoot)
    try {
        [void](Invoke-DockerCommand `
            -Arguments @(
                "compose",
                "up",
                "-d",
                "redis"
            ) `
            -StreamOutput)
    }
    finally {
        Pop-Location
    }

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 500 `
        -FailureMessage "Redis 재기동 시간 초과" `
        -Condition {
            return (
                Invoke-RedisScalar -RedisArguments @("PING")
            ) -eq "PONG"
        }
}

function Wait-ForApplicationRecovery {
    & (Join-Path $PSScriptRoot "wait-for-services.ps1") `
        -Glowroot:$Glowroot `
        -TimeoutSeconds $TimeoutSeconds `
        -EnvironmentFile $EnvironmentFile
}

function Publish-BillingEvent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReservationId,

        [Parameter(Mandatory = $true)]
        [string]$EventId,

        [Parameter(Mandatory = $true)]
        [long]$TargetAppliedAmount,

        [Parameter(Mandatory = $true)]
        [DateTimeOffset]$OccurredAt
    )

    [void](& (
        Join-Path $PSScriptRoot "publish-billing-event.ps1"
    ) `
        -ReservationId $ReservationId `
        -EventId $EventId `
        -EventType "CHARGED" `
        -TargetAppliedAmount $TargetAppliedAmount `
        -Sequence 1 `
        -OccurredAt $OccurredAt `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)
}

function Request-Decision {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RequestId
    )

    return Invoke-SignedJsonRequest `
        -Method "POST" `
        -Path "/internal/v1/pacing/decisions/decide" `
        -ClientId "ad-server" `
        -Body @{
            requestId = $RequestId
            campaignId = $campaignId
            requestedAt = [DateTimeOffset]::UtcNow.ToString("o")
        }
}

try {
    Write-Host ""
    Write-Host "============================================"
    Write-Host " Redis Failure and Recovery Test"
    Write-Host "============================================"
    Write-Host "campaign: $campaignId"

    Write-Step "0. 테스트 환경 준비"

    if (-not $SkipStart) {
        & (Join-Path $PSScriptRoot "start-local.ps1") `
            -NoBuild:$NoBuild `
            -Monitoring:$Monitoring `
            -Glowroot:$Glowroot `
            -GlowrootHome $GlowrootHome `
            -TimeoutSeconds $TimeoutSeconds `
            -EnvironmentFile $EnvironmentFile
    }
    else {
        Wait-ForApplicationRecovery
    }

    Write-Step "1. 캠페인과 PostgreSQL 복구 기준 상태 생성"

    $now = [DateTimeOffset]::UtcNow
    [void](& (
        Join-Path $PSScriptRoot "upsert-campaign.ps1"
    ) `
        -CampaignId $campaignId `
        -Status "ACTIVE" `
        -PacingStrategy "ASAP" `
        -TotalBudget 100000 `
        -DailyBudgetLimit 100000 `
        -StartAt $now.AddMinutes(-5) `
        -EndAt $now.AddDays(1) `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)

    $initialDecision = & (
        Join-Path $PSScriptRoot "request-pacing-decision.ps1"
    ) `
        -CampaignId $campaignId `
        -RequestId (New-TestIdentifier -Prefix "redis-recovery-init") `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected "PASS" `
        -Actual ([string]$initialDecision.Json.decision) `
        -Message "초기 페이싱 판단이 PASS가 아닙니다."

    foreach ($reservationId in @(
        $confirmedReservationId,
        $pendingReservationId
    )) {
        [void](& (
            Join-Path $PSScriptRoot "reserve-budget.ps1"
        ) `
            -CampaignId $campaignId `
            -ReservationId $reservationId `
            -Amount $reservationAmount `
            -ExpectedStatus 201 `
            -PassThru `
            -EnvironmentFile $EnvironmentFile)
    }

    Wait-ForReservationState `
        -ReservationId $confirmedReservationId `
        -ExpectedState "RESERVED|1000|0|0"
    Wait-ForReservationState `
        -ReservationId $pendingReservationId `
        -ExpectedState "RESERVED|1000|0|0"

    Publish-BillingEvent `
        -ReservationId $confirmedReservationId `
        -EventId $initialChargeEventId `
        -TargetAppliedAmount $initialChargeAmount `
        -OccurredAt ([DateTimeOffset]::UtcNow)

    Wait-ForBillingStatus `
        -EventId $initialChargeEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $confirmedReservationId `
        -ExpectedState "CONFIRMED|1000|900|1"

    $stateBeforeLoss = & (
        Join-Path $PSScriptRoot "inspect-state.ps1"
    ) `
        -CampaignId $campaignId `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected "900" `
        -Actual ([string]$stateBeforeLoss.totalSpentAmount) `
        -Message "장애 전 전체 확정 소진액이 다릅니다."
    Assert-Equal `
        -Expected "1000" `
        -Actual ([string]$stateBeforeLoss.totalReservedAmount) `
        -Message "장애 전 전체 예약액이 다릅니다."

    $campaignLiteral = ConvertTo-SqlLiteral -Value $campaignId

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage "페이싱 상태가 PostgreSQL에 저장되지 않았습니다." `
        -Condition {
            $count = Get-DbValue -Sql (
                "SELECT COUNT(*) FROM pacing_state_snapshot " +
                "WHERE campaign_id = $campaignLiteral"
            )
            return $count -eq "1"
        }

    $pacingVersionBeforeLoss = Get-DbValue -Sql (
        "SELECT version FROM pacing_state_snapshot " +
        "WHERE campaign_id = $campaignLiteral"
    )

    Assert-Equal `
        -Expected 4 `
        -Actual (Get-CoreRedisKeyCount) `
        -Message "장애 전 Redis 핵심 상태가 준비되지 않았습니다."

    $apiRecoveryBefore = Get-MetricCount `
        -BaseUrl (Get-ApiBaseUrl) `
        -MetricName "pacing_infrastructure_budget_recovery_total" `
        -LabelName "outcome" `
        -LabelValue "RECOVERED"

    Write-Step "2. 테스트 캠페인 Redis 상태 유실 및 Redis 중지"

    Remove-TestCampaignRedisState

    Assert-Equal `
        -Expected 0 `
        -Actual (Get-CoreRedisKeyCount) `
        -Message "테스트 캠페인 Redis Key가 완전히 삭제되지 않았습니다."

    Stop-TestRedis
    $redisStopped = $true

    Write-Step "3. Redis 장애 중 API의 503 응답 검증"

    $outageResponse = Request-Decision `
        -RequestId (New-TestIdentifier -Prefix "redis-outage-decision")

    Assert-HttpStatus `
        -Response $outageResponse `
        -ExpectedStatus 503 `
        -Operation "Redis 장애 중 페이싱 판단"
    Assert-Equal `
        -Expected "STORAGE_UNAVAILABLE" `
        -Actual ([string]$outageResponse.Json.code) `
        -Message "Redis 장애 응답 코드가 STORAGE_UNAVAILABLE이 아닙니다."

    Write-Step "4. Redis 재기동 및 API 상태 복구"

    Start-TestRedis
    $redisStopped = $false
    Wait-ForApplicationRecovery

    Assert-Equal `
        -Expected 0 `
        -Actual (Get-CoreRedisKeyCount) `
        -Message "삭제한 Redis 상태가 요청 전에 예상치 않게 생성됐습니다."

    $recoveryDecision = Request-Decision `
        -RequestId (New-TestIdentifier -Prefix "redis-recovered-decision")

    Assert-HttpStatus `
        -Response $recoveryDecision `
        -ExpectedStatus 200 `
        -Operation "Redis 복구 후 페이싱 판단"

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage "API 예산 복구 메트릭이 증가하지 않았습니다." `
        -Condition {
            $current = Get-MetricCount `
                -BaseUrl (Get-ApiBaseUrl) `
                -MetricName "pacing_infrastructure_budget_recovery_total" `
                -LabelName "outcome" `
                -LabelValue "RECOVERED"

            return ($current - $apiRecoveryBefore) -ge 1
        }

    Assert-Equal `
        -Expected 4 `
        -Actual (Get-CoreRedisKeyCount) `
        -Message "API 요청이 Redis 판단 상태를 모두 복구하지 못했습니다."
    Assert-Equal `
        -Expected 900L `
        -Actual (
            Get-RedisLong `
                -Key $totalBudgetKey `
                -Field "totalSpentAmount"
        ) `
        -Message "복구된 전체 확정 소진액이 다릅니다."
    Assert-Equal `
        -Expected 1000L `
        -Actual (
            Get-RedisLong `
                -Key $totalBudgetKey `
                -Field "totalReservedAmount"
        ) `
        -Message "복구된 전체 예약액이 다릅니다."

    $recoveredPacingVersion = Get-RedisLong `
        -Key $pacingStateKey `
        -Field "version"

    if ($recoveredPacingVersion -lt [long]$pacingVersionBeforeLoss) {
        throw (
            "복구된 페이싱 version이 PostgreSQL snapshot보다 작습니다. " +
            "snapshot=$pacingVersionBeforeLoss redis=$recoveredPacingVersion"
        )
    }

    Write-Step "5. Redis 장애 중 Worker 과금 이벤트 재시도 검증"

    $workerRecoveryBefore = Get-MetricCount `
        -BaseUrl (Get-WorkerBaseUrl) `
        -MetricName "pacing_infrastructure_budget_recovery_total" `
        -LabelName "outcome" `
        -LabelValue "RECOVERED"

    Remove-TestCampaignRedisState
    Stop-TestRedis
    $redisStopped = $true

    Publish-BillingEvent `
        -ReservationId $pendingReservationId `
        -EventId $outageChargeEventId `
        -TargetAppliedAmount $outageChargeAmount `
        -OccurredAt ([DateTimeOffset]::UtcNow)

    Wait-ForBillingStatus `
        -EventId $outageChargeEventId `
        -ExpectedStatus "RECEIVED" `
        -WaitTimeoutSeconds 15

    Start-TestRedis
    $redisStopped = $false
    Wait-ForApplicationRecovery

    Wait-ForBillingStatus `
        -EventId $outageChargeEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $pendingReservationId `
        -ExpectedState "CONFIRMED|1000|800|1"

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage "Worker 예산 복구 메트릭이 증가하지 않았습니다." `
        -Condition {
            $current = Get-MetricCount `
                -BaseUrl (Get-WorkerBaseUrl) `
                -MetricName "pacing_infrastructure_budget_recovery_total" `
                -LabelName "outcome" `
                -LabelValue "RECOVERED"

            return ($current - $workerRecoveryBefore) -ge 1
        }

    Write-Step "6. Redis와 PostgreSQL 최종 정합성 검증"

    $finalDecision = Request-Decision `
        -RequestId (New-TestIdentifier -Prefix "redis-final-decision")

    Assert-HttpStatus `
        -Response $finalDecision `
        -ExpectedStatus 200 `
        -Operation "최종 페이싱 판단"

    $finalState = & (
        Join-Path $PSScriptRoot "inspect-state.ps1"
    ) `
        -CampaignId $campaignId `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected ([string]$expectedFinalSpend) `
        -Actual ([string]$finalState.totalSpentAmount) `
        -Message "최종 전체 확정 소진액이 다릅니다."
    Assert-Equal `
        -Expected "0" `
        -Actual ([string]$finalState.totalReservedAmount) `
        -Message "최종 전체 예약액이 0이 아닙니다."
    Assert-Equal `
        -Expected ([string]$expectedFinalSpend) `
        -Actual ([string]$finalState.dailySpentAmount) `
        -Message "최종 일일 확정 소진액이 다릅니다."
    Assert-Equal `
        -Expected "0" `
        -Actual ([string]$finalState.dailyReservedAmount) `
        -Message "최종 일일 예약액이 0이 아닙니다."

    Assert-Equal `
        -Expected "CONFIRMED|1000|900|1" `
        -Actual (
            Get-ReservationState `
                -ReservationId $confirmedReservationId
        ) `
        -Message "기존 확정 예약이 복구 과정에서 변경됐습니다."
    Assert-Equal `
        -Expected "CONFIRMED|1000|800|1" `
        -Actual (
            Get-ReservationState `
                -ReservationId $pendingReservationId
        ) `
        -Message "장애 중 과금된 예약의 최종 상태가 다릅니다."

    $completedCount = Get-DbValue -Sql (
        "SELECT COUNT(*) FROM billing_event be " +
        "JOIN budget_reservation br " +
        "ON br.reservation_id = be.reservation_id " +
        "WHERE br.campaign_id = $campaignLiteral " +
        "AND be.processing_status = 'COMPLETED'"
    )
    $deadLetterCount = Get-DbValue -Sql (
        "SELECT COUNT(*) FROM billing_event be " +
        "JOIN budget_reservation br " +
        "ON br.reservation_id = be.reservation_id " +
        "WHERE br.campaign_id = $campaignLiteral " +
        "AND be.processing_status = 'DEAD_LETTER'"
    )

    Assert-Equal `
        -Expected "2" `
        -Actual $completedCount `
        -Message "완료된 과금 이벤트 수가 2건이 아닙니다."
    Assert-Equal `
        -Expected "0" `
        -Actual $deadLetterCount `
        -Message "Redis 장애 복구 중 DLT 이벤트가 발생했습니다."

    Write-Host ""
    Write-Host "Redis Failure and Recovery Test SUCCESS" `
        -ForegroundColor Green
    Write-Host "campaignId:          $campaignId"
    Write-Host "final spent amount:  $expectedFinalSpend"
    Write-Host "verified:"
    Write-Host "  - Redis 장애 중 API 503 응답"
    Write-Host "  - PostgreSQL 기반 캠페인/예산/페이싱 상태 복구"
    Write-Host "  - Redis 장애 중 Kafka 과금 이벤트 재시도"
    Write-Host "  - 재기동 후 Redis/PostgreSQL 최종 정합성"
}
catch {
    Write-Host ""
    Write-Host "Redis Failure and Recovery Test FAILED" `
        -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red

    Write-Host ""
    Write-Host "최근 애플리케이션 로그" -ForegroundColor Yellow

    Push-Location (Get-ProjectRoot)
    try {
        [void](Invoke-DockerCommand `
            -Arguments @(
                "compose",
                "--profile",
                "application",
                "logs",
                "--tail",
                "250",
                "pacing-api",
                "pacing-worker"
            ) `
            -StreamOutput)
    }
    finally {
        Pop-Location
    }

    throw
}
finally {
    if ($redisStopped) {
        Write-Host ""
        Write-Host "실패 후 Redis 컨테이너를 복구합니다." `
            -ForegroundColor Yellow

        try {
            Start-TestRedis
            $redisStopped = $false
        }
        catch {
            Write-Warning (
                "Redis 컨테이너 자동 복구에 실패했습니다: " +
                $_.Exception.Message
            )
        }
    }
}
