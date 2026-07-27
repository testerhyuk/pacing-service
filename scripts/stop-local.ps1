[CmdletBinding()]
param(
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

Write-Step "로컬 페이싱 스택 중지"

Push-Location (Get-ProjectRoot)
try {
    [void](Invoke-DockerCommand `
        -Arguments @(
            "compose",
            "--profile",
            "application",
            "--profile",
            "monitoring",
            "down",
            "--remove-orphans"
        ) `
        -StreamOutput)
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "로컬 스택 중지 완료" -ForegroundColor Green
