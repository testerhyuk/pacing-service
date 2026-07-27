[CmdletBinding()]
param(
    [string]$CampaignId,

    [ValidateSet("ACTIVE", "PAUSED", "ENDED")]
    [string]$Status = "ACTIVE",

    [ValidateSet("EVEN", "PEAK_WEIGHTED", "ASAP")]
    [string]$PacingStrategy = "ASAP",

    [long]$TotalBudget = 100000,

    [long]$DailyBudgetLimit = 50000,

    [DateTimeOffset]$StartAt,

    [DateTimeOffset]$EndAt,

    [switch]$PassThru,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ([string]::IsNullOrWhiteSpace($CampaignId)) {
    $CampaignId = New-TestIdentifier -Prefix "campaign"
}

if ($null -eq $StartAt -or
    $StartAt -eq [DateTimeOffset]::MinValue) {
    $StartAt = [DateTimeOffset]::UtcNow.AddMinutes(-5)
}

if ($null -eq $EndAt -or
    $EndAt -eq [DateTimeOffset]::MinValue) {
    $EndAt = [DateTimeOffset]::UtcNow.AddDays(1)
}

if ($TotalBudget -lt 0) {
    throw "전체 예산은 0 이상이어야 합니다."
}

if ($DailyBudgetLimit -lt 0 -or
    $DailyBudgetLimit -gt $TotalBudget) {
    throw "일일 예산은 0 이상이며 전체 예산 이하여야 합니다."
}

$path = "/internal/admin/v1/campaigns/$CampaignId"
$body = @{
    status = $Status
    startAt = $StartAt.ToUniversalTime().ToString("o")
    endAt = $EndAt.ToUniversalTime().ToString("o")
    pacingStrategy = $PacingStrategy
    totalBudget = $TotalBudget
    dailyBudgetLimit = $DailyBudgetLimit
}

Write-Step "캠페인 등록 또는 변경: $CampaignId"

$response = Invoke-SignedJsonRequest `
    -Method "PUT" `
    -Path $path `
    -ClientId "operation-server" `
    -Body $body

Assert-HttpStatus `
    -Response $response `
    -ExpectedStatus 200 `
    -Operation "캠페인 등록"

Write-Host $response.Body

if ($PassThru) {
    return $response
}
