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

$campaignId = New-TestIdentifier -Prefix "e2e-campaign"
$reservationId = New-TestIdentifier -Prefix "e2e-reservation"
$decisionRequestId = New-TestIdentifier -Prefix "e2e-decision"
$chargeEventId = New-TestIdentifier -Prefix "e2e-charge"
$adjustEventId = New-TestIdentifier -Prefix "e2e-adjust"
$cancelEventId = New-TestIdentifier -Prefix "e2e-cancel"
$reservationAmount = 1000L
$adjustedAmount = 1200L

function Get-ReservationState {
    param(
        [string]$ExpectedReservationId
    )

    $literal = ConvertTo-SqlLiteral -Value $ExpectedReservationId
    return Invoke-PostgresScalar -Sql (
        "SELECT status || '|' || applied_amount " +
        "FROM budget_reservation " +
        "WHERE reservation_id = $literal"
    )
}

function Get-BillingStatus {
    param(
        [string]$ExpectedEventId
    )

    $literal = ConvertTo-SqlLiteral -Value $ExpectedEventId
    return Invoke-PostgresScalar -Sql (
        "SELECT processing_status " +
        "FROM billing_event WHERE event_id = $literal"
    )
}

function Wait-ForBillingResult {
    param(
        [string]$EventId,
        [string]$ExpectedReservationState
    )

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 1000 `
        -FailureMessage "과금 이벤트 처리 시간 초과: $EventId" `
        -Condition {
            $billingStatus = Get-BillingStatus `
                -ExpectedEventId $EventId
            $reservationState = Get-ReservationState `
                -ExpectedReservationId $reservationId

            return $billingStatus -eq "COMPLETED" -and
                $reservationState -eq $ExpectedReservationState
        }
}

try {
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

    Write-Step "1. ACTIVE ASAP 캠페인 생성"

    $campaignResponse = & (
        Join-Path $PSScriptRoot "upsert-campaign.ps1"
    ) `
        -CampaignId $campaignId `
        -Status "ACTIVE" `
        -PacingStrategy "ASAP" `
        -TotalBudget 100000 `
        -DailyBudgetLimit 50000 `
        -StartAt ([DateTimeOffset]::UtcNow.AddMinutes(-5)) `
        -EndAt ([DateTimeOffset]::UtcNow.AddDays(1)) `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected $campaignId `
        -Actual $campaignResponse.Json.campaignId `
        -Message "생성된 campaignId가 요청과 다릅니다."

    Write-Step "2. 동적 피크 정책 변경과 조회"

    [void](& (
        Join-Path $PSScriptRoot "update-peak-policy.ps1"
    ) `
        -StartTime "18:00:00" `
        -EndTime "23:00:00" `
        -ZoneId "Asia/Seoul" `
        -NormalWeight 0.5 `
        -PeakWeight 1.5 `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)

    $peakPolicyPath = "/internal/admin/v1/peak-policy"
    $peakPolicyResponse = Invoke-SignedJsonRequest `
        -Method "GET" `
        -Path $peakPolicyPath `
        -ClientId "operation-server"

    Assert-HttpStatus `
        -Response $peakPolicyResponse `
        -ExpectedStatus 200 `
        -Operation "피크 정책 조회"
    Assert-Equal `
        -Expected "Asia/Seoul" `
        -Actual ([string]$peakPolicyResponse.Json.zoneId) `
        -Message "피크 정책 타임존이 다릅니다."

    Write-Step "3. HMAC 인증과 서비스 권한 검증"

    $campaignPath = "/internal/admin/v1/campaigns/$campaignId"
    $forbiddenResponse = Invoke-SignedJsonRequest `
        -Method "GET" `
        -Path $campaignPath `
        -ClientId "ad-server"

    Assert-HttpStatus `
        -Response $forbiddenResponse `
        -ExpectedStatus 403 `
        -Operation "권한 없는 관리자 API 호출"

    $invalidSignatureBody = @{
        requestId = New-TestIdentifier -Prefix "invalid-signature"
        campaignId = $campaignId
        requestedAt = [DateTimeOffset]::UtcNow.ToString("o")
    }
    $invalidSignatureResponse = Invoke-SignedJsonRequest `
        -Method "POST" `
        -Path "/internal/v1/pacing/decisions/decide" `
        -ClientId "ad-server" `
        -Secret "invalid-local-test-secret-key-000000" `
        -Body $invalidSignatureBody

    Assert-HttpStatus `
        -Response $invalidSignatureResponse `
        -ExpectedStatus 401 `
        -Operation "잘못된 HMAC 서명"

    $replayNow = [DateTimeOffset]::UtcNow
    $replayTimestamp = $replayNow.ToUnixTimeSeconds().ToString()
    $replayNonce = New-TestIdentifier -Prefix "replay"

    $firstReplayResponse = Invoke-SignedJsonRequest `
        -Method "GET" `
        -Path $campaignPath `
        -ClientId "operation-server" `
        -Timestamp $replayTimestamp `
        -Nonce $replayNonce
    $secondReplayResponse = Invoke-SignedJsonRequest `
        -Method "GET" `
        -Path $campaignPath `
        -ClientId "operation-server" `
        -Timestamp $replayTimestamp `
        -Nonce $replayNonce

    Assert-HttpStatus `
        -Response $firstReplayResponse `
        -ExpectedStatus 200 `
        -Operation "최초 nonce 요청"
    Assert-HttpStatus `
        -Response $secondReplayResponse `
        -ExpectedStatus 401 `
        -Operation "nonce 재사용 요청"

    Write-Step "4. 페이싱 판단과 Redis 예산 상태 초기화"

    $decisionResponse = & (
        Join-Path $PSScriptRoot "request-pacing-decision.ps1"
    ) `
        -CampaignId $campaignId `
        -RequestId $decisionRequestId `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected "PASS" `
        -Actual ([string]$decisionResponse.Json.decision) `
        -Message "ASAP 캠페인의 최초 판단이 PASS가 아닙니다."

    Write-Step "5. 신규 예약과 멱등 재요청 검증"

    $createdReservation = & (
        Join-Path $PSScriptRoot "reserve-budget.ps1"
    ) `
        -CampaignId $campaignId `
        -ReservationId $reservationId `
        -Amount $reservationAmount `
        -ExpectedStatus 201 `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected $true `
        -Actual ([bool]$createdReservation.Json.created) `
        -Message "신규 예약의 created 값이 true가 아닙니다."
    Assert-Equal `
        -Expected "RESERVED" `
        -Actual ([string]$createdReservation.Json.status) `
        -Message "신규 예약 상태가 RESERVED가 아닙니다."

    $existingReservation = & (
        Join-Path $PSScriptRoot "reserve-budget.ps1"
    ) `
        -CampaignId $campaignId `
        -ReservationId $reservationId `
        -Amount $reservationAmount `
        -ExpectedStatus 200 `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected $false `
        -Actual ([bool]$existingReservation.Json.created) `
        -Message "중복 예약의 created 값이 false가 아닙니다."

    Write-Step "6. 예약 충돌과 예산 부족 검증"

    $conflictResponse = Invoke-SignedJsonRequest `
        -Method "POST" `
        -Path "/internal/v1/budget-reservations" `
        -ClientId "auction-server" `
        -Body @{
            reservationId = $reservationId
            campaignId = $campaignId
            amount = $reservationAmount + 1
        }

    Assert-HttpStatus `
        -Response $conflictResponse `
        -ExpectedStatus 409 `
        -Operation "동일 ID의 다른 금액 예약"

    $insufficientResponse = Invoke-SignedJsonRequest `
        -Method "POST" `
        -Path "/internal/v1/budget-reservations" `
        -ClientId "auction-server" `
        -Body @{
            reservationId = New-TestIdentifier -Prefix "too-large"
            campaignId = $campaignId
            amount = 50001
        }

    Assert-HttpStatus `
        -Response $insufficientResponse `
        -ExpectedStatus 409 `
        -Operation "일일 예산 초과 예약"

    Write-Step "7. 예약 PostgreSQL 저장 대기"

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 1000 `
        -FailureMessage "예약이 PostgreSQL에 저장되지 않았습니다." `
        -Condition {
            return (
                Get-ReservationState `
                    -ExpectedReservationId $reservationId
            ) -eq "RESERVED|0"
        }

    Write-Step "8. CHARGED 과금 확정과 중복 이벤트 검증"

    $chargeOccurredAt = [DateTimeOffset]::UtcNow
    [void](& (
        Join-Path $PSScriptRoot "publish-billing-event.ps1"
    ) `
        -ReservationId $reservationId `
        -EventId $chargeEventId `
        -EventType "CHARGED" `
        -ActualAmount $reservationAmount `
        -OccurredAt $chargeOccurredAt `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)

    Wait-ForBillingResult `
        -EventId $chargeEventId `
        -ExpectedReservationState "CONFIRMED|1000"

    [void](& (
        Join-Path $PSScriptRoot "publish-billing-event.ps1"
    ) `
        -ReservationId $reservationId `
        -EventId $chargeEventId `
        -EventType "CHARGED" `
        -ActualAmount $reservationAmount `
        -OccurredAt $chargeOccurredAt `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)

    Start-Sleep -Seconds 2

    $chargeLiteral = ConvertTo-SqlLiteral -Value $chargeEventId
    $chargeEventCount = Invoke-PostgresScalar -Sql (
        "SELECT COUNT(*) FROM billing_event " +
        "WHERE event_id = $chargeLiteral"
    )

    Assert-Equal `
        -Expected "1" `
        -Actual $chargeEventCount `
        -Message "중복 CHARGED 이벤트가 두 번 저장됐습니다."

    Write-Step "9. ADJUSTED 과금 보정 검증"

    [void](& (
        Join-Path $PSScriptRoot "publish-billing-event.ps1"
    ) `
        -ReservationId $reservationId `
        -EventId $adjustEventId `
        -EventType "ADJUSTED" `
        -ActualAmount $adjustedAmount `
        -OccurredAt ([DateTimeOffset]::UtcNow) `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)

    Wait-ForBillingResult `
        -EventId $adjustEventId `
        -ExpectedReservationState "CONFIRMED|1200"

    Write-Step "10. CANCELLED 과금 취소 검증"

    [void](& (
        Join-Path $PSScriptRoot "publish-billing-event.ps1"
    ) `
        -ReservationId $reservationId `
        -EventId $cancelEventId `
        -EventType "CANCELLED" `
        -ActualAmount $adjustedAmount `
        -OccurredAt ([DateTimeOffset]::UtcNow) `
        -PassThru `
        -EnvironmentFile $EnvironmentFile)

    Wait-ForBillingResult `
        -EventId $cancelEventId `
        -ExpectedReservationState "CANCELLED|0"

    Write-Step "11. Redis와 PostgreSQL 최종 상태 검증"

    $finalState = & (
        Join-Path $PSScriptRoot "inspect-state.ps1"
    ) `
        -CampaignId $campaignId `
        -ReservationId $reservationId `
        -PassThru `
        -EnvironmentFile $EnvironmentFile

    Assert-Equal `
        -Expected "0" `
        -Actual $finalState.totalSpentAmount `
        -Message "전체 확정 소진액이 취소 후 0이 아닙니다."
    Assert-Equal `
        -Expected "0" `
        -Actual $finalState.totalReservedAmount `
        -Message "전체 예약액이 0이 아닙니다."
    Assert-Equal `
        -Expected "0" `
        -Actual $finalState.dailySpentAmount `
        -Message "일일 확정 소진액이 취소 후 0이 아닙니다."
    Assert-Equal `
        -Expected "0" `
        -Actual $finalState.dailyReservedAmount `
        -Message "일일 예약액이 0이 아닙니다."
    Assert-Equal `
        -Expected "CANCELLED|$reservationAmount|0" `
        -Actual $finalState.postgresReservation `
        -Message "PostgreSQL 예약 최종 상태가 다릅니다."

    $reservationLiteral = ConvertTo-SqlLiteral `
        -Value $reservationId
    $completedBillingCount = Invoke-PostgresScalar -Sql (
        "SELECT COUNT(*) FROM billing_event " +
        "WHERE reservation_id = $reservationLiteral " +
        "AND processing_status = 'COMPLETED'"
    )

    Assert-Equal `
        -Expected "3" `
        -Actual $completedBillingCount `
        -Message "완료된 과금 이벤트 수가 CHARGED/ADJUSTED/CANCELLED 3건이 아닙니다."

    Write-Step "12. 메트릭 엔드포인트 확인"

    $apiMetrics = Invoke-UnsignedRequest -Uri (
        (Get-ApiBaseUrl) + "/actuator/prometheus"
    )
    $workerMetrics = Invoke-UnsignedRequest -Uri (
        (Get-WorkerBaseUrl) + "/actuator/prometheus"
    )

    Assert-HttpStatus `
        -Response $apiMetrics `
        -ExpectedStatus 200 `
        -Operation "API Prometheus 메트릭"
    Assert-HttpStatus `
        -Response $workerMetrics `
        -ExpectedStatus 200 `
        -Operation "Worker Prometheus 메트릭"

    Write-Host ""
    Write-Host "E2E 스모크 테스트 성공" -ForegroundColor Green
    Write-Host "campaignId:    $campaignId"
    Write-Host "reservationId: $reservationId"
    Write-Host "PR 흐름: HMAC → 판단 → 예약 → 과금 → 보정 → 취소"
}
catch {
    Write-Host ""
    Write-Host "E2E 스모크 테스트 실패" -ForegroundColor Red
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
