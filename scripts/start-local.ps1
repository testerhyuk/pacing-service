[CmdletBinding()]
param(
    [switch]$Monitoring,
    [switch]$Glowroot,
    [switch]$NoBuild,
    [int]$TimeoutSeconds = 240,
    [string]$GlowrootHome,
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

& (Join-Path $PSScriptRoot "check-prerequisites.ps1") `
    -EnvironmentFile $EnvironmentFile

$arguments = @("compose")

if ($Glowroot) {
    & (Join-Path $PSScriptRoot "setup-glowroot.ps1") `
        -GlowrootHome $GlowrootHome

    $arguments += @(
        "-f",
        "compose.yml",
        "-f",
        "compose.glowroot.yml"
    )
}

$arguments += @(
    "--profile",
    "application"
)

if ($Monitoring) {
    $arguments += @(
        "--profile",
        "monitoring"
    )
}

$arguments += @(
    "up",
    "-d"
)

if (-not $NoBuild) {
    $arguments += "--build"
}

Write-Step "로컬 페이싱 스택 시작"

Push-Location (Get-ProjectRoot)
try {
    [void](Invoke-DockerCommand `
        -Arguments $arguments `
        -StreamOutput)
}
finally {
    Pop-Location
}

& (Join-Path $PSScriptRoot "wait-for-services.ps1") `
    -Glowroot:$Glowroot `
    -TimeoutSeconds $TimeoutSeconds `
    -EnvironmentFile $EnvironmentFile

Write-Host ""
Write-Host "로컬 스택 시작 완료" -ForegroundColor Green
