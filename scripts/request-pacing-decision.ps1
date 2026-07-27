[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CampaignId,

    [string]$RequestId,

    [DateTimeOffset]$RequestedAt,

    [switch]$PassThru,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ([string]::IsNullOrWhiteSpace($RequestId)) {
    $RequestId = New-TestIdentifier -Prefix "decision"
}

if ($null -eq $RequestedAt -or
    $RequestedAt -eq [DateTimeOffset]::MinValue) {
    $RequestedAt = [DateTimeOffset]::UtcNow
}

$path = "/internal/v1/pacing/decisions/decide"
$body = @{
    requestId = $RequestId
    campaignId = $CampaignId
    requestedAt = $RequestedAt.ToUniversalTime().ToString("o")
}

Write-Step "페이싱 판단 요청: $RequestId"

$response = Invoke-SignedJsonRequest `
    -Method "POST" `
    -Path $path `
    -ClientId "ad-server" `
    -Body $body

Assert-HttpStatus `
    -Response $response `
    -ExpectedStatus 200 `
    -Operation "페이싱 판단"

Write-Host $response.Body

if ($PassThru) {
    return $response
}
