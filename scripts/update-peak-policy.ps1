[CmdletBinding()]
param(
    [string]$StartTime = "18:00:00",
    [string]$EndTime = "23:00:00",
    [string]$ZoneId = "Asia/Seoul",
    [double]$NormalWeight = 0.5,
    [double]$PeakWeight = 1.5,
    [switch]$PassThru,
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($NormalWeight -le 0 -or $PeakWeight -le 0) {
    throw "트래픽 가중치는 0보다 커야 합니다."
}

if ($PeakWeight -le $NormalWeight) {
    throw "피크 가중치는 일반 가중치보다 커야 합니다."
}

$path = "/internal/admin/v1/peak-policy"
$body = @{
    startTime = $StartTime
    endTime = $EndTime
    zoneId = $ZoneId
    normalWeight = $NormalWeight
    peakWeight = $PeakWeight
}

Write-Step "피크 정책 변경"

$response = Invoke-SignedJsonRequest `
    -Method "PUT" `
    -Path $path `
    -ClientId "operation-server" `
    -Body $body

Assert-HttpStatus `
    -Response $response `
    -ExpectedStatus 200 `
    -Operation "피크 정책 변경"

Write-Host $response.Body

if ($PassThru) {
    return $response
}

