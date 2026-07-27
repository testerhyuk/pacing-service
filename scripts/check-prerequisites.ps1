[CmdletBinding()]
param(
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

$loadedEnvironment = Import-LocalEnvironment `
    -EnvironmentFile $EnvironmentFile

Write-Step "필수 파일 확인"

$requiredFiles = @(
    "gradlew.bat",
    "compose.yml",
    "docker\pacing-api.Dockerfile",
    "docker\pacing-worker.Dockerfile"
)

foreach ($relativePath in $requiredFiles) {
    $path = Join-Path (Get-ProjectRoot) $relativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "필수 파일이 없습니다: $path"
    }
}

Write-Step "Docker 및 Docker Compose 확인"

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker 명령을 찾을 수 없습니다. Docker Desktop을 설치하고 실행하세요."
}

try {
    [void](Invoke-DockerCommand -Arguments @("version"))
}
catch {
    throw "Docker 엔진에 연결할 수 없습니다. Docker Desktop 실행 상태를 확인하세요. $($_.Exception.Message)"
}

[void](Invoke-DockerCommand -Arguments @(
    "compose",
    "version"
))

Write-Step "로컬 HMAC 비밀키 확인"

foreach ($clientId in @(
    "ad-server",
    "auction-server",
    "operation-server"
)) {
    $secret = Get-HmacSecret -ClientId $clientId
    $byteCount = [Text.Encoding]::UTF8.GetByteCount($secret)

    if ($byteCount -lt 32) {
        throw "$clientId HMAC 비밀키는 UTF-8 기준 32바이트 이상이어야 합니다."
    }
}

Write-Host ""
Write-Host "사전 점검 완료" -ForegroundColor Green
Write-Host "환경변수 파일: $loadedEnvironment"
