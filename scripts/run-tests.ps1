[CmdletBinding()]
param(
    [switch]$SkipBootJar
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

$gradleWrapper = Join-Path (Get-ProjectRoot) "gradlew.bat"

Write-Step "전체 모듈 테스트 실행"

Push-Location (Get-ProjectRoot)
try {
    [void](Invoke-NativeCommand `
        -FilePath $gradleWrapper `
        -Arguments @(
            "test",
            "--no-daemon"
        ) `
        -StreamOutput)

    if (-not $SkipBootJar) {
        Write-Step "API와 Worker 실행 JAR 빌드"

        [void](Invoke-NativeCommand `
            -FilePath $gradleWrapper `
            -Arguments @(
                ":pacing-api:bootJar",
                ":pacing-worker:bootJar",
                "--no-daemon"
            ) `
            -StreamOutput)
    }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "Gradle 검증 완료" -ForegroundColor Green
