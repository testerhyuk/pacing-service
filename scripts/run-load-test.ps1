[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CampaignId,

    [int]$Rate = 100,

    [string]$Duration = "30s",

    [int]$PreAllocatedVUs = 50,

    [int]$MaxVUs = 200,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($Rate -le 0) {
    throw "초당 요청 수는 0보다 커야 합니다."
}

if ($PreAllocatedVUs -le 0 -or
    $MaxVUs -lt $PreAllocatedVUs) {
    throw "VUs 설정이 올바르지 않습니다."
}

$apiHealth = Invoke-UnsignedRequest -Uri (
    (Get-ApiBaseUrl) + "/actuator/health"
)
Assert-HttpStatus `
    -Response $apiHealth `
    -ExpectedStatus 200 `
    -Operation "부하 테스트 전 API health check"

$secret = Get-HmacSecret -ClientId "ad-server"
$apiBaseUrl = Get-ApiBaseUrl
$dockerNetwork = Get-EnvironmentValue `
    -Name "PACING_K6_DOCKER_NETWORK" `
    -DefaultValue "pacing-service_pacing-network"
$k6Image = Get-EnvironmentValue `
    -Name "PACING_K6_IMAGE" `
    -DefaultValue "grafana/k6:latest"
$loadScriptDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "load")
)

Write-Step "k6 페이싱 판단 부하 테스트"
Write-Host "rate:     $Rate req/s"
Write-Host "duration: $Duration"
Write-Host "campaign: $CampaignId"

[void](Invoke-DockerCommand `
    -Arguments @(
        "run",
        "--rm",
        "--network",
        $dockerNetwork,
        "-e",
        "BASE_URL=$apiBaseUrl",
        "-e",
        "CLIENT_ID=ad-server",
        "-e",
        "SECRET=$secret",
        "-e",
        "CAMPAIGN_ID=$CampaignId",
        "-e",
        "RATE=$Rate",
        "-e",
        "DURATION=$Duration",
        "-e",
        "PRE_ALLOCATED_VUS=$PreAllocatedVUs",
        "-e",
        "MAX_VUS=$MaxVUs",
        "-v",
        "${loadScriptDirectory}:/scripts:ro",
        $k6Image,
        "run",
        "/scripts/pacing-decision.js"
    ) `
    -StreamOutput)

Write-Host ""
Write-Host "k6 부하 테스트 성공" -ForegroundColor Green
