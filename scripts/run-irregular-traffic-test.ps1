[CmdletBinding()]
param(
    [long]$ReservationAmount = 100,
    [long]$TotalBudget = 10000000,
    [switch]$SkipStart,
    [switch]$NoBuild,
    [switch]$Monitoring,
    [int]$TimeoutSeconds = 300,
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($ReservationAmount -le 0) {
    throw "예약 금액은 0보다 커야 합니다."
}

if ($TotalBudget -le 0) {
    throw "전체 예산은 0보다 커야 합니다."
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
    -Operation "불규칙 트래픽 테스트 전 API health check"

$campaignId = New-TestIdentifier -Prefix "irregular-traffic"
$reservationPrefix = New-TestIdentifier `
    -Prefix "irregular-traffic-reservation"
$now = [DateTimeOffset]::UtcNow

Write-Step "불규칙 트래픽 테스트용 EVEN 캠페인 생성"

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
$scriptPath = Join-Path `
    $loadScriptDirectory `
    "irregular-traffic-pacing.js"

if (-not (Test-Path -LiteralPath $scriptPath)) {
    throw "부하 테스트 스크립트를 찾을 수 없습니다: $scriptPath"
}

Write-Step "불규칙 반복 급증 트래픽 테스트"
Write-Host "campaign: $campaignId"
Write-Host "pattern: 50(40s) -> 300(10s) -> 50(25s) -> 250(30s) -> 80(15s) -> 400(8s) -> 50(60s) RPS"
Write-Host "amount: $ReservationAmount"
Write-Host "dashboard: Grafana > Pacing > pacing-traffic-response"

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
        "-v",
        "${loadScriptDirectory}:/scripts:ro",
        $k6Image,
        "run",
        "/scripts/irregular-traffic-pacing.js"
    ) `
    -StreamOutput)

Write-Host ""
Write-Host "Irregular traffic test SUCCESS" -ForegroundColor Green
Write-Host "Grafana에서 불규칙 입력 RPS, EWMA 관측 RPS, 적용 PASS 비율을 비교하세요." -ForegroundColor Yellow
