[CmdletBinding()]
param(
    [switch]$Glowroot,
    [int]$TimeoutSeconds = 240,
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

$apiHealthUrl = (Get-ApiBaseUrl) + "/actuator/health"
$workerHealthUrl = (Get-WorkerBaseUrl) + "/actuator/health"

Write-Step "pacing-api 기동 대기"
Wait-ForHttpHealth `
    -Name "pacing-api" `
    -Url $apiHealthUrl `
    -TimeoutSeconds $TimeoutSeconds

Write-Step "pacing-worker 기동 대기"
Wait-ForHttpHealth `
    -Name "pacing-worker" `
    -Url $workerHealthUrl `
    -TimeoutSeconds $TimeoutSeconds

if ($Glowroot) {
    $apiGlowrootUrl = Get-ApiGlowrootUrl
    $workerGlowrootUrl = Get-WorkerGlowrootUrl

    Write-Step "pacing-api Glowroot 기동 대기"
    Wait-ForHttpHealth `
        -Name "pacing-api Glowroot" `
        -Url $apiGlowrootUrl `
        -TimeoutSeconds $TimeoutSeconds

    Write-Step "pacing-worker Glowroot 기동 대기"
    Wait-ForHttpHealth `
        -Name "pacing-worker Glowroot" `
        -Url $workerGlowrootUrl `
        -TimeoutSeconds $TimeoutSeconds
}

Write-Host ""
Write-Host "애플리케이션 준비 완료" -ForegroundColor Green
Write-Host "API:    $apiHealthUrl"
Write-Host "Worker: $workerHealthUrl"

if ($Glowroot) {
    Write-Host "API Glowroot:    $apiGlowrootUrl"
    Write-Host "Worker Glowroot: $workerGlowrootUrl"
}
