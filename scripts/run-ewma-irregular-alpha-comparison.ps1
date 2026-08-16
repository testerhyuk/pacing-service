[CmdletBinding()]
param(
    [double[]]$Alphas = @(0.2, 0.3, 0.5, 0.7),
    [int]$RunsPerAlpha = 3,
    [long]$ReservationAmount = 100,
    [long]$TotalBudget = 10000000,
    [int]$StabilizationSeconds = 15,
    [int]$TimeoutSeconds = 300,
    [switch]$NoBuild,
    [switch]$SkipStart,
    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

$previousAlpha = [Environment]::GetEnvironmentVariable(
    "PACING_EWMA_ALPHA",
    "Process"
)

try {
    [void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

    if ($Alphas.Count -eq 0) {
        throw "비교할 alpha가 최소 하나는 필요합니다."
    }

    foreach ($alpha in $Alphas) {
        if ($alpha -le 0.0 -or $alpha -gt 1.0) {
            throw "EWMA alpha는 0보다 크고 1 이하여야 합니다: $alpha"
        }
    }

    if ($RunsPerAlpha -le 0) {
        throw "alpha별 반복 횟수는 0보다 커야 합니다."
    }

    if ($ReservationAmount -le 0 -or $TotalBudget -le 0) {
        throw "예약 금액과 전체 예산은 0보다 커야 합니다."
    }

    if ($StabilizationSeconds -lt 0) {
        throw "안정화 대기 시간은 음수일 수 없습니다."
    }

    $projectRoot = Get-ProjectRoot
    $scenarioSeconds = 188
    $totalRuns = $Alphas.Count * $RunsPerAlpha
    $estimatedMinutes = [Math]::Ceiling(
        ($scenarioSeconds * $totalRuns) / 60.0
    )

    Write-Step "EWMA alpha 불규칙 트래픽 비교 테스트"
    Write-Host "alphas: $($Alphas -join ', ')"
    Write-Host "runs per alpha: $RunsPerAlpha"
    Write-Host "scenario: 50 -> 300 -> 50 -> 250 -> 80 -> 400 -> 50 RPS"
    Write-Host "estimated minimum duration: about $estimatedMinutes minutes"
    Write-Host "Grafana: Pacing > pacing-traffic-response"

    if ($SkipStart) {
        & (Join-Path $PSScriptRoot "wait-for-services.ps1") `
            -TimeoutSeconds $TimeoutSeconds `
            -EnvironmentFile $EnvironmentFile
    }
    else {
        & (Join-Path $PSScriptRoot "start-local.ps1") `
            -Monitoring `
            -NoBuild:$NoBuild `
            -TimeoutSeconds $TimeoutSeconds `
            -EnvironmentFile $EnvironmentFile
    }

    foreach ($alpha in $Alphas) {
        $formattedAlpha = $alpha.ToString(
            "0.###",
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        [Environment]::SetEnvironmentVariable(
            "PACING_EWMA_ALPHA",
            $formattedAlpha,
            "Process"
        )

        Write-Step "EWMA alpha = $formattedAlpha"

        Push-Location $projectRoot
        try {
            [void](Invoke-DockerCommand `
                -Arguments @(
                    "compose",
                    "--profile", "application",
                    "up", "-d",
                    "--no-build",
                    "--force-recreate",
                    "pacing-api"
                ) `
                -StreamOutput)
        }
        finally {
            Pop-Location
        }

        & (Join-Path $PSScriptRoot "wait-for-services.ps1") `
            -TimeoutSeconds $TimeoutSeconds `
            -EnvironmentFile $EnvironmentFile

        if ($StabilizationSeconds -gt 0) {
            Write-Host "API 재기동 후 ${StabilizationSeconds}초 안정화 대기"
            Start-Sleep -Seconds $StabilizationSeconds
        }

        for ($run = 1; $run -le $RunsPerAlpha; $run++) {
            Write-Step "alpha=$formattedAlpha run=$run/$RunsPerAlpha"

            & (Join-Path $PSScriptRoot "run-irregular-traffic-test.ps1") `
                -ReservationAmount $ReservationAmount `
                -TotalBudget $TotalBudget `
                -SkipStart `
                -EnvironmentFile $EnvironmentFile

            Write-Host (
                "alpha=$formattedAlpha run=$run/$RunsPerAlpha 완료"
            ) -ForegroundColor Green
        }
    }

    Write-Host ""
    Write-Host "EWMA irregular alpha comparison SUCCESS" `
        -ForegroundColor Green
    Write-Host "Compare irregular traffic / EWMA observed RPS / PASS rate in Grafana." `
        -ForegroundColor Yellow
}
finally {
    [Environment]::SetEnvironmentVariable(
        "PACING_EWMA_ALPHA",
        $previousAlpha,
        "Process"
    )
}
