[CmdletBinding()]
param(
    [double[]]$Alphas = @(0.2, 0.3, 0.5, 0.7),

    [int]$WarmupRate = 50,
    [int]$SpikeRate = 300,
    [int]$RecoveryRate = 50,
    [int]$WarmupSeconds = 90,
    [int]$SpikeSeconds = 90,
    [int]$RecoverySeconds = 120,
    [int]$RunsPerAlpha = 3,
    [long]$ReservationAmount = 100,
    [long]$TotalBudget = 10000000,
    [int]$PreAllocatedVUs = 100,
    [int]$MaxVUs = 300,
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

    $projectRoot = Get-ProjectRoot
    $scenarioSeconds = $WarmupSeconds + $SpikeSeconds + $RecoverySeconds
    $totalRuns = $Alphas.Count * $RunsPerAlpha
    $estimatedMinutes = [Math]::Ceiling(
        ($scenarioSeconds * $totalRuns) / 60.0
    )

    Write-Step "EWMA alpha 비교 테스트"
    Write-Host "alphas: $($Alphas -join ', ')"
    Write-Host "runs per alpha: $RunsPerAlpha"
    Write-Host "scenario: $WarmupRate RPS -> $SpikeRate RPS -> $RecoveryRate RPS"
    Write-Host "duration: ${WarmupSeconds}s -> ${SpikeSeconds}s -> ${RecoverySeconds}s"
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

        # SkipStart와 관계없이 alpha를 컨테이너에 반영한다.
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

        for ($run = 1; $run -le $RunsPerAlpha; $run++) {
            Write-Step "alpha=$formattedAlpha run=$run/$RunsPerAlpha"

            & (Join-Path $PSScriptRoot "run-traffic-response-test.ps1") `
                -WarmupRate $WarmupRate `
                -SpikeRate $SpikeRate `
                -RecoveryRate $RecoveryRate `
                -WarmupSeconds $WarmupSeconds `
                -SpikeSeconds $SpikeSeconds `
                -RecoverySeconds $RecoverySeconds `
                -ReservationAmount $ReservationAmount `
                -TotalBudget $TotalBudget `
                -PreAllocatedVUs $PreAllocatedVUs `
                -MaxVUs $MaxVUs `
                -SkipStart `
                -EnvironmentFile $EnvironmentFile

            Write-Host (
                "alpha=$formattedAlpha run=$run/$RunsPerAlpha 완료"
            ) -ForegroundColor Green
        }
    }

    Write-Host ""
    Write-Host "EWMA alpha comparison SUCCESS" -ForegroundColor Green
    Write-Host "Compare Actual Traffic / EWMA Observed RPS / PASS Rate in Grafana." -ForegroundColor Yellow
}
finally {
    [Environment]::SetEnvironmentVariable(
        "PACING_EWMA_ALPHA",
        $previousAlpha,
        "Process"
    )
}
