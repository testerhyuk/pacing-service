[CmdletBinding()]
param(
    [int]$WarmupRate = 50,

    [int]$SpikeRate = 300,

    [int]$RecoveryRate = 50,

    [int]$WarmupSeconds = 90,

    [int]$SpikeSeconds = 90,

    [int]$RecoverySeconds = 120,

    [long]$ReservationAmount = 100,

    [long]$TotalBudget = 10000000,

    [int]$PreAllocatedVUs = 100,

    [int]$MaxVUs = 300,

    [switch]$SkipStart,

    [switch]$NoBuild,

    [switch]$Monitoring,

    [int]$TimeoutSeconds = 300,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

foreach ($rate in @($WarmupRate, $SpikeRate, $RecoveryRate)) {
    if ($rate -le 0) {
        throw "각 구간의 초당 판단 요청 수는 0보다 커야 합니다."
    }
}

foreach ($duration in @(
        $WarmupSeconds,
        $SpikeSeconds,
        $RecoverySeconds
    )) {
    if ($duration -le 0) {
        throw "각 구간의 지속 시간은 0보다 커야 합니다."
    }
}

if ($ReservationAmount -le 0) {
    throw "예약 금액은 0보다 커야 합니다."
}

if ($TotalBudget -le 0) {
    throw "전체 예산은 0보다 커야 합니다."
}

if ($PreAllocatedVUs -le 0 -or $MaxVUs -lt $PreAllocatedVUs) {
    throw "VUs 설정이 올바르지 않습니다."
}

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

$apiHealth = Invoke-UnsignedRequest -Uri (
    (Get-ApiBaseUrl) + "/actuator/health"
)
Assert-HttpStatus `
    -Response $apiHealth `
    -ExpectedStatus 200 `
    -Operation "트래픽 반응 테스트 전 API health check"

$campaignId = New-TestIdentifier -Prefix "traffic-response"
$reservationPrefix = New-TestIdentifier -Prefix "traffic-response-reservation"
$now = [DateTimeOffset]::UtcNow

Write-Step "트래픽 반응 테스트용 EVEN 캠페인 생성"

& (Join-Path $PSScriptRoot "upsert-campaign.ps1") `
    -CampaignId $campaignId `
    -Status ACTIVE `
    -PacingStrategy EVEN `
    -TotalBudget $TotalBudget `
    -DailyBudgetLimit $TotalBudget `
    -StartAt $now.AddSeconds(-30) `
    -EndAt $now.AddHours(1) `
    -EnvironmentFile $EnvironmentFile

$secret = Get-HmacSecret -ClientId "ad-server"
$k6Image = Get-EnvironmentValue `
    -Name "PACING_K6_IMAGE" `
    -DefaultValue "grafana/k6:latest"
$loadScriptDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "load")
)
$scriptPath = Join-Path $loadScriptDirectory "traffic-shaped-pacing.js"

if (-not (Test-Path -LiteralPath $scriptPath)) {
    throw "부하 테스트 스크립트를 찾을 수 없습니다: $scriptPath"
}

Write-Step "구간형 트래픽 반응 부하 테스트"
Write-Host "campaign:  $campaignId"
Write-Host "warmup:    $WarmupRate req/s for $WarmupSeconds sec"
Write-Host "spike:     $SpikeRate req/s for $SpikeSeconds sec"
Write-Host "recovery:  $RecoveryRate req/s for $RecoverySeconds sec"
Write-Host "amount:    $ReservationAmount"
Write-Host "dashboard: Grafana > Pacing > Pacing traffic response"

[void](Invoke-DockerCommand `
    -Arguments @(
        "run",
        "--rm",
        "--network",
        "pacing-service_pacing-network",
        "-e",
        "BASE_URL=http://pacing-api:8080",
        "-e",
        "CLIENT_ID=ad-server",
        "-e",
        "SECRET=$secret",
        "-e",
        "CAMPAIGN_ID=$campaignId",
        "-e",
        "RESERVATION_PREFIX=$reservationPrefix",
        "-e",
        "RESERVATION_AMOUNT=$ReservationAmount",
        "-e",
        "WARMUP_RATE=$WarmupRate",
        "-e",
        "SPIKE_RATE=$SpikeRate",
        "-e",
        "RECOVERY_RATE=$RecoveryRate",
        "-e",
        "WARMUP_DURATION=${WarmupSeconds}s",
        "-e",
        "SPIKE_DURATION=${SpikeSeconds}s",
        "-e",
        "RECOVERY_DURATION=${RecoverySeconds}s",
        "-e",
        "SPIKE_START=${WarmupSeconds}s",
        "-e",
        "RECOVERY_START=$($WarmupSeconds + $SpikeSeconds)s",
        "-e",
        "PRE_ALLOCATED_VUS=$PreAllocatedVUs",
        "-e",
        "MAX_VUS=$MaxVUs",
        "-v",
        "${loadScriptDirectory}:/scripts:ro",
        $k6Image,
        "run",
        "/scripts/traffic-shaped-pacing.js"
    ) `
    -StreamOutput)

Write-Host ""
Write-Host "Traffic response test SUCCESS" -ForegroundColor Green
Write-Host "Grafana에서 입력 RPS, 1분 관측 RPS, 적용 PASS 비율을 비교하세요." -ForegroundColor Yellow
