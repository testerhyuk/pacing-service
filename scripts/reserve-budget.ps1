[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CampaignId,

    [long]$Amount = 1000,

    [string]$ReservationId,

    [ValidateSet("ad-server", "auction-server")]
    [string]$ClientId = "auction-server",

    [int[]]$ExpectedStatus = @(200, 201),

    [switch]$PassThru,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ([string]::IsNullOrWhiteSpace($ReservationId)) {
    $ReservationId = New-TestIdentifier -Prefix "reservation"
}

if ($Amount -le 0) {
    throw "예약 금액은 0보다 커야 합니다."
}

$path = "/internal/v1/budget-reservations"
$body = @{
    reservationId = $ReservationId
    campaignId = $CampaignId
    amount = $Amount
}

Write-Step "예산 예약 요청: $ReservationId"

$response = Invoke-SignedJsonRequest `
    -Method "POST" `
    -Path $path `
    -ClientId $ClientId `
    -Body $body

Assert-HttpStatus `
    -Response $response `
    -ExpectedStatus $ExpectedStatus `
    -Operation "예산 예약"

Write-Host $response.Body

if ($PassThru) {
    return $response
}

