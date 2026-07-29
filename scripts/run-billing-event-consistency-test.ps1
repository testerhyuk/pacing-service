[CmdletBinding()]
param(
    [switch]$SkipStart,

    [switch]$NoBuild,

    [switch]$Monitoring,

    [switch]$Glowroot,

    [int]$TimeoutSeconds = 180,

    [string]$GlowrootHome,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($TimeoutSeconds -le 0) {
    throw "TimeoutSeconds는 0보다 커야 합니다."
}

$campaignId = New-TestIdentifier -Prefix "billing-consistency-campaign"
$duplicateReservationId = New-TestIdentifier -Prefix "billing-duplicate-reservation"
$outOfOrderReservationId = New-TestIdentifier -Prefix "billing-out-of-order-reservation"
$staleReservationId = New-TestIdentifier -Prefix "billing-stale-reservation"
$lifecycleReservationId = New-TestIdentifier -Prefix "billing-lifecycle-reservation"

$duplicateChargeEventId = New-TestIdentifier -Prefix "billing-duplicate-charge"
$outOfOrderChargeEventId = New-TestIdentifier -Prefix "billing-out-of-order-charge"
$outOfOrderAdjustEventId = New-TestIdentifier -Prefix "billing-out-of-order-adjust"
$staleChargeEventId = New-TestIdentifier -Prefix "billing-stale-charge"
$staleAdjustEventId = New-TestIdentifier -Prefix "billing-stale-adjust"
$lifecycleChargeEventId = New-TestIdentifier -Prefix "billing-lifecycle-charge"
$lifecycleAdjustEventId = New-TestIdentifier -Prefix "billing-lifecycle-adjust"
$lifecycleCancelEventId = New-TestIdentifier -Prefix "billing-lifecycle-cancel"

$reservationAmount = 1000L
$duplicateChargeAmount = 900L
$outOfOrderChargeAmount = 800L
$outOfOrderAdjustedAmount = 1200L
$staleChargeAmount = 700L
$staleAdjustedAmount = 950L
$lifecycleAdjustedAmount = 1100L
$duplicateDeliveryCount = 4
$expectedFinalSpend = (
    $duplicateChargeAmount +
    $outOfOrderAdjustedAmount +
    $staleChargeAmount
)

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

function Get-BillingProcessingStatus {
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

function Get-BillingResultState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EventId
    )

    $literal = ConvertTo-SqlLiteral -Value $EventId

    return Get-DbValue -Sql (
        "SELECT processing_status || '|' || result_status || '|' || " +
        "result_applied_amount || '|' || reservation_version " +
        "FROM billing_event WHERE event_id = $literal"
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
        [string]$ExpectedStatus
    )

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage (
            "과금 이벤트 상태 확인 시간 초과: " +
            "eventId=$EventId expected=$ExpectedStatus"
        ) `
        -Condition {
            return (
                Get-BillingProcessingStatus -EventId $EventId
            ) -eq $ExpectedStatus
        }
}

function Get-WorkerBillingMetricCount {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EventType,

        [Parameter(Mandatory = $true)]
        [string]$Status
    )

    $response = Invoke-UnsignedRequest -Uri (
        (Get-WorkerBaseUrl) + "/actuator/prometheus"
    )

    Assert-HttpStatus `
        -Response $response `
        -ExpectedStatus 200 `
        -Operation "Worker Prometheus 메트릭 조회"

    [double]$total = 0
    $lines = $response.Body -split "`r?`n"

    foreach ($line in $lines) {
        if ($line -notmatch (
            '^pacing_worker_billing_seconds_count' +
            '\{(?<labels>[^}]*)\}\s+' +
            '(?<value>[-+0-9.eE]+)\s*$'
        )) {
            continue
        }

        $labels = [string]$Matches["labels"]
        if (-not $labels.Contains("eventType=`"$EventType`"")) {
            continue
        }
        if (-not $labels.Contains("status=`"$Status`"")) {
            continue
        }

        $total += [double]::Parse(
            $Matches["value"],
            [Globalization.CultureInfo]::InvariantCulture
        )
    }

    return $total
}

function Publish-BillingEvent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReservationId,

        [Parameter(Mandatory = $true)]
        [string]$EventId,

        [Parameter(Mandatory = $true)]
        [ValidateSet("CHARGED", "CANCELLED", "ADJUSTED")]
        [string]$EventType,

        [Parameter(Mandatory = $true)]
        [long]$ActualAmount,

        [Parameter(Mandatory = $true)]
        [DateTimeOffset]$OccurredAt
    )

    [void](& (
        Join-Path $PSScriptRoot "publish-billing-event.ps1"
    ) `
        -ReservationId $ReservationId `
        -EventId $EventId `
        -EventType $EventType `
        -ActualAmount $ActualAmount `
        -OccurredAt $OccurredAt `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)
}

function Create-Reservation {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReservationId
    )

    [void](& (
        Join-Path $PSScriptRoot "reserve-budget.ps1"
    ) `
        -CampaignId $campaignId `
        -ReservationId $ReservationId `
        -Amount $reservationAmount `
        -ExpectedStatus 201 `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)
}

try {
    Write-Host ""
    Write-Host "============================================"
    Write-Host " Billing Event Consistency Test"
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
        & (Join-Path $PSScriptRoot "wait-for-services.ps1") `
            -Glowroot:$Glowroot `
            -TimeoutSeconds $TimeoutSeconds `
            -EnvironmentFile $EnvironmentFile
    }

    Write-Step "1. 캠페인 생성 및 예산 상태 초기화"

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
        -RequestId (New-TestIdentifier -Prefix "billing-consistency-init") `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected "PASS" `
        -Actual ([string]$initialDecision.Json.decision) `
        -Message "초기 페이싱 판단이 PASS가 아닙니다."

    Write-Step "2. 독립 시나리오용 예약 4건 생성"

    $reservationIds = @(
        $duplicateReservationId,
        $outOfOrderReservationId,
        $staleReservationId,
        $lifecycleReservationId
    )

    foreach ($reservationId in $reservationIds) {
        Create-Reservation -ReservationId $reservationId
    }

    foreach ($reservationId in $reservationIds) {
        Wait-ForReservationState `
            -ReservationId $reservationId `
            -ExpectedState "RESERVED|1000|0|0"
    }

    $eventBaseTime = [DateTimeOffset]::UtcNow.AddSeconds(1)

    Write-Step "3. 동일 eventId 재전송의 멱등성 검증"

    Publish-BillingEvent `
        -ReservationId $duplicateReservationId `
        -EventId $duplicateChargeEventId `
        -EventType "CHARGED" `
        -ActualAmount $duplicateChargeAmount `
        -OccurredAt $eventBaseTime.AddSeconds(10)

    Wait-ForBillingStatus `
        -EventId $duplicateChargeEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $duplicateReservationId `
        -ExpectedState "CONFIRMED|1000|900|1"

    $duplicateMetricBefore = Get-WorkerBillingMetricCount `
        -EventType "CHARGED" `
        -Status "DUPLICATE"

    for ($index = 0; $index -lt $duplicateDeliveryCount; $index++) {
        Publish-BillingEvent `
            -ReservationId $duplicateReservationId `
            -EventId $duplicateChargeEventId `
            -EventType "CHARGED" `
            -ActualAmount $duplicateChargeAmount `
            -OccurredAt $eventBaseTime.AddSeconds(10)
    }

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage "중복 과금 이벤트가 모두 처리되지 않았습니다." `
        -Condition {
            $current = Get-WorkerBillingMetricCount `
                -EventType "CHARGED" `
                -Status "DUPLICATE"
            return (
                $current - $duplicateMetricBefore
            ) -ge $duplicateDeliveryCount
        }

    $duplicateEventLiteral = ConvertTo-SqlLiteral `
        -Value $duplicateChargeEventId
    $duplicateEventRows = Get-DbValue -Sql (
        "SELECT COUNT(*) FROM billing_event " +
        "WHERE event_id = $duplicateEventLiteral"
    )

    Assert-Equal `
        -Expected "1" `
        -Actual $duplicateEventRows `
        -Message "동일 eventId가 PostgreSQL에 중복 저장됐습니다."
    Assert-Equal `
        -Expected "CONFIRMED|1000|900|1" `
        -Actual (
            Get-ReservationState `
                -ReservationId $duplicateReservationId
        ) `
        -Message "중복 이벤트가 예약 금액이나 버전을 다시 변경했습니다."

    Write-Step "4. ADJUSTED 선도착 후 CHARGED 처리 검증"

    Publish-BillingEvent `
        -ReservationId $outOfOrderReservationId `
        -EventId $outOfOrderAdjustEventId `
        -EventType "ADJUSTED" `
        -ActualAmount $outOfOrderAdjustedAmount `
        -OccurredAt $eventBaseTime.AddSeconds(30)

    Wait-ForBillingStatus `
        -EventId $outOfOrderAdjustEventId `
        -ExpectedStatus "RECEIVED"

    Publish-BillingEvent `
        -ReservationId $outOfOrderReservationId `
        -EventId $outOfOrderChargeEventId `
        -EventType "CHARGED" `
        -ActualAmount $outOfOrderChargeAmount `
        -OccurredAt $eventBaseTime.AddSeconds(20)

    Wait-ForBillingStatus `
        -EventId $outOfOrderChargeEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForBillingStatus `
        -EventId $outOfOrderAdjustEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $outOfOrderReservationId `
        -ExpectedState "CONFIRMED|1000|1200|2"

    Write-Step "5. 늦게 도착한 과거 이벤트 무시 검증"

    Publish-BillingEvent `
        -ReservationId $staleReservationId `
        -EventId $staleChargeEventId `
        -EventType "CHARGED" `
        -ActualAmount $staleChargeAmount `
        -OccurredAt $eventBaseTime.AddSeconds(50)

    Wait-ForBillingStatus `
        -EventId $staleChargeEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $staleReservationId `
        -ExpectedState "CONFIRMED|1000|700|1"

    $staleMetricBefore = Get-WorkerBillingMetricCount `
        -EventType "ADJUSTED" `
        -Status "STALE"

    Publish-BillingEvent `
        -ReservationId $staleReservationId `
        -EventId $staleAdjustEventId `
        -EventType "ADJUSTED" `
        -ActualAmount $staleAdjustedAmount `
        -OccurredAt $eventBaseTime.AddSeconds(40)

    Wait-ForBillingStatus `
        -EventId $staleAdjustEventId `
        -ExpectedStatus "COMPLETED"

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 200 `
        -FailureMessage "지연 이벤트가 STALE로 처리되지 않았습니다." `
        -Condition {
            $current = Get-WorkerBillingMetricCount `
                -EventType "ADJUSTED" `
                -Status "STALE"
            return ($current - $staleMetricBefore) -ge 1
        }

    Assert-Equal `
        -Expected "COMPLETED|CONFIRMED|700|1" `
        -Actual (
            Get-BillingResultState -EventId $staleAdjustEventId
        ) `
        -Message "STALE 이벤트 결과가 현재 예약 상태를 보존하지 못했습니다."
    Assert-Equal `
        -Expected "CONFIRMED|1000|700|1" `
        -Actual (
            Get-ReservationState -ReservationId $staleReservationId
        ) `
        -Message "과거 이벤트가 최신 예약 상태를 덮어썼습니다."

    Write-Step "6. CHARGED → ADJUSTED → CANCELLED 상태 전이 검증"

    Publish-BillingEvent `
        -ReservationId $lifecycleReservationId `
        -EventId $lifecycleChargeEventId `
        -EventType "CHARGED" `
        -ActualAmount $reservationAmount `
        -OccurredAt $eventBaseTime.AddSeconds(60)

    Wait-ForBillingStatus `
        -EventId $lifecycleChargeEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $lifecycleReservationId `
        -ExpectedState "CONFIRMED|1000|1000|1"

    Publish-BillingEvent `
        -ReservationId $lifecycleReservationId `
        -EventId $lifecycleAdjustEventId `
        -EventType "ADJUSTED" `
        -ActualAmount $lifecycleAdjustedAmount `
        -OccurredAt $eventBaseTime.AddSeconds(70)

    Wait-ForBillingStatus `
        -EventId $lifecycleAdjustEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $lifecycleReservationId `
        -ExpectedState "CONFIRMED|1000|1100|2"

    Publish-BillingEvent `
        -ReservationId $lifecycleReservationId `
        -EventId $lifecycleCancelEventId `
        -EventType "CANCELLED" `
        -ActualAmount $lifecycleAdjustedAmount `
        -OccurredAt $eventBaseTime.AddSeconds(80)

    Wait-ForBillingStatus `
        -EventId $lifecycleCancelEventId `
        -ExpectedStatus "COMPLETED"
    Wait-ForReservationState `
        -ReservationId $lifecycleReservationId `
        -ExpectedState "CANCELLED|1000|0|3"

    Write-Step "7. PostgreSQL 및 Redis 최종 정합성 검증"

    $finalState = & (
        Join-Path $PSScriptRoot "inspect-state.ps1"
    ) `
        -CampaignId $campaignId `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected ([string]$expectedFinalSpend) `
        -Actual ([string]$finalState.totalSpentAmount) `
        -Message "Redis 전체 확정 소진액이 예상과 다릅니다."
    Assert-Equal `
        -Expected "0" `
        -Actual ([string]$finalState.totalReservedAmount) `
        -Message "Redis 전체 예약액이 0이 아닙니다."
    Assert-Equal `
        -Expected ([string]$expectedFinalSpend) `
        -Actual ([string]$finalState.dailySpentAmount) `
        -Message "Redis 일일 확정 소진액이 예상과 다릅니다."
    Assert-Equal `
        -Expected "0" `
        -Actual ([string]$finalState.dailyReservedAmount) `
        -Message "Redis 일일 예약액이 0이 아닙니다."

    $campaignLiteral = ConvertTo-SqlLiteral -Value $campaignId
    $billingEventCount = Get-DbValue -Sql (
        "SELECT COUNT(*) FROM billing_event be " +
        "JOIN budget_reservation br " +
        "ON br.reservation_id = be.reservation_id " +
        "WHERE br.campaign_id = $campaignLiteral"
    )
    $completedBillingEventCount = Get-DbValue -Sql (
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
        -Expected "8" `
        -Actual $billingEventCount `
        -Message "고유 과금 이벤트 저장 건수가 8건이 아닙니다."
    Assert-Equal `
        -Expected "8" `
        -Actual $completedBillingEventCount `
        -Message "모든 고유 과금 이벤트가 완료되지 않았습니다."
    Assert-Equal `
        -Expected "0" `
        -Actual $deadLetterCount `
        -Message "정상 시나리오에서 DLT 이벤트가 발생했습니다."

    Write-Host ""
    Write-Host "Billing Event Consistency Test SUCCESS" `
        -ForegroundColor Green
    Write-Host "campaignId:              $campaignId"
    Write-Host "unique billing events:   8"
    Write-Host "duplicate redeliveries:  $duplicateDeliveryCount"
    Write-Host "final spent amount:      $expectedFinalSpend"
    Write-Host "verified:"
    Write-Host "  - 동일 eventId 중복 반영 방지"
    Write-Host "  - ADJUSTED 선도착 후 재시도 처리"
    Write-Host "  - 늦게 도착한 과거 이벤트의 STALE 처리"
    Write-Host "  - CHARGED/ADJUSTED/CANCELLED 최종 정합성"
}
catch {
    Write-Host ""
    Write-Host "Billing Event Consistency Test FAILED" `
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
                "200",
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
