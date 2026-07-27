[CmdletBinding()]
param(
    [switch]$Force,
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

if (-not $Force) {
    throw "PostgreSQL, Redis, Kafka 로컬 볼륨을 삭제합니다. 실행하려면 -Force를 지정하세요."
}

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

$projectRoot = [System.IO.Path]::GetFullPath(
    (Get-ProjectRoot)
)
$expectedRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..")
)

if ($projectRoot -ne $expectedRoot) {
    throw "프로젝트 루트 검증에 실패했습니다: $projectRoot"
}

Write-Step "로컬 컨테이너와 데이터 볼륨 삭제"

Push-Location $projectRoot
try {
    [void](Invoke-DockerCommand `
        -Arguments @(
            "compose",
            "--profile",
            "application",
            "--profile",
            "monitoring",
            "down",
            "--volumes",
            "--remove-orphans"
        ) `
        -StreamOutput)
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "로컬 데이터 초기화 완료" -ForegroundColor Green
