[CmdletBinding()]
param(
    [int]$DecisionRate = 1000,

    [int]$BillingRate = 500,

    [int]$DurationSeconds = 240,

    [int]$ReservationSetupRate = 500,

    [long]$Amount = 10,

    [int]$StabilizationSeconds = 15,

    [int]$TimeoutSeconds = 600,

    [switch]$SkipStart,

    [switch]$NoBuild,

    [switch]$Monitoring,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($DecisionRate -le 0) {
    throw "DecisionRate must be greater than 0."
}
if ($BillingRate -le 0) {
    throw "BillingRate must be greater than 0."
}
if ($DurationSeconds -lt 180) {
    throw (
        "DurationSeconds must be at least 180 seconds so the load " +
        "continues throughout the rolling restart."
    )
}
if ($ReservationSetupRate -le 0) {
    throw "ReservationSetupRate must be greater than 0."
}
if ($Amount -le 0) {
    throw "Amount must be greater than 0."
}
if ($StabilizationSeconds -lt 5) {
    throw "StabilizationSeconds must be at least 5 seconds."
}
if ($TimeoutSeconds -le 0) {
    throw "TimeoutSeconds must be greater than 0."
}

$secondaryApiPort = Get-EnvironmentValue `
    -Name "PACING_MULTI_API_SECONDARY_PORT" `
    -DefaultValue "18082"
$tertiaryApiPort = Get-EnvironmentValue `
    -Name "PACING_MULTI_API_TERTIARY_PORT" `
    -DefaultValue "18084"
$secondaryWorkerPort = Get-EnvironmentValue `
    -Name "PACING_MULTI_WORKER_SECONDARY_PORT" `
    -DefaultValue "18083"
$loadBalancerPort = Get-EnvironmentValue `
    -Name "PACING_MULTI_API_LB_PORT" `
    -DefaultValue "18080"
$loadBalancerAdminPort = Get-EnvironmentValue `
    -Name "PACING_MULTI_API_LB_ADMIN_PORT" `
    -DefaultValue "19999"

$primaryApiUrl = Get-ApiBaseUrl
$primaryWorkerUrl = Get-WorkerBaseUrl
$secondaryApiUrl = "http://localhost:$secondaryApiPort"
$tertiaryApiUrl = "http://localhost:$tertiaryApiPort"
$secondaryWorkerUrl = "http://localhost:$secondaryWorkerPort"
$loadBalancerUrl = "http://localhost:$loadBalancerPort"
$apiServices = @(
    [pscustomobject]@{
        Name = "primary API"
        BaseUrl = $primaryApiUrl
    },
    [pscustomobject]@{
        Name = "secondary API"
        BaseUrl = $secondaryApiUrl
    },
    [pscustomobject]@{
        Name = "tertiary API"
        BaseUrl = $tertiaryApiUrl
    }
)
$workerServices = @(
    [pscustomobject]@{
        Name = "primary worker"
        BaseUrl = $primaryWorkerUrl
    },
    [pscustomobject]@{
        Name = "secondary worker"
        BaseUrl = $secondaryWorkerUrl
    }
)
$projectRoot = Get-ProjectRoot
$fullSystemScript = Join-Path `
    $PSScriptRoot `
    "run-full-system-load-test.ps1"
$runId = New-TestIdentifier -Prefix "rolling-restart"
$reportDirectory = Join-Path `
    $projectRoot `
    "build\reports\rolling-restart\$runId"
$loadStdoutFile = Join-Path `
    $reportDirectory `
    "full-system.stdout.log"
$loadStderrFile = Join-Path `
    $reportDirectory `
    "full-system.stderr.log"
$loadProcess = $null
$testFailure = $null

[void](New-Item `
    -ItemType Directory `
    -Path $reportDirectory `
    -Force)

function Get-PrometheusMetricTotal {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,

        [Parameter(Mandatory = $true)]
        [string]$MetricName
    )

    $response = Invoke-WebRequest `
        -UseBasicParsing `
        -TimeoutSec 15 `
        -Uri (
            $BaseUrl.TrimEnd("/") +
            "/actuator/prometheus"
        )
    $text = [string]$response.Content
    $escapedName = [regex]::Escape($MetricName)
    [double]$total = 0
    $matched = $false

    foreach ($line in ($text -split "`r?`n")) {
        if ($line -notmatch (
            "^$escapedName" +
            '(?:\{[^}]*\})?\s+' +
            '(?<value>[-+0-9.eE]+)\s*$'
        )) {
            continue
        }

        $total += [double]::Parse(
            $Matches["value"],
            [Globalization.CultureInfo]::InvariantCulture
        )
        $matched = $true
    }

    if (-not $matched) {
        return 0.0
    }

    return $total
}

function Get-CombinedApiDecisionCount {
    return (
        (Get-PrometheusMetricTotal `
            -BaseUrl $primaryApiUrl `
            -MetricName "pacing_api_decision_seconds_count") +
        (Get-PrometheusMetricTotal `
            -BaseUrl $secondaryApiUrl `
            -MetricName "pacing_api_decision_seconds_count") +
        (Get-PrometheusMetricTotal `
            -BaseUrl $tertiaryApiUrl `
            -MetricName "pacing_api_decision_seconds_count")
    )
}

function Set-ApiRouting {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet(
            "all",
            "without-primary",
            "without-secondary",
            "without-tertiary"
        )]
        [string]$Mode
    )

    Write-Step "Route API traffic: $Mode"

    $states = @{
        "pacing-api" = "ready"
        "pacing-api-2" = "ready"
        "pacing-api-3" = "ready"
    }

    switch ($Mode) {
        "without-primary" {
            $states["pacing-api"] = "drain"
        }
        "without-secondary" {
            $states["pacing-api-2"] = "drain"
        }
        "without-tertiary" {
            $states["pacing-api-3"] = "drain"
        }
    }

    foreach ($serverName in $states.Keys) {
        Set-HaproxyServerRuntimeState `
            -AdminPort ([int]$loadBalancerAdminPort) `
            -BackendName "pacing_api" `
            -ServerName $serverName `
            -State $states[$serverName]
    }

    Wait-ForHttpHealth `
        -Name "pacing-api load balancer" `
        -Url "$loadBalancerUrl/actuator/health" `
        -TimeoutSeconds $TimeoutSeconds

    # 기존 keep-alive 요청이 제외된 인스턴스에서 빠져나갈 시간을 준다.
    Start-Sleep -Seconds 3
}

function Restart-And-Wait {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ContainerName,

        [Parameter(Mandatory = $true)]
        [string]$HealthUrl
    )

    Write-Step "Rolling restart: $ContainerName"

    [void](Invoke-DockerCommand `
        -Arguments @(
            "restart",
            "--time",
            "30",
            $ContainerName
        ) `
        -StreamOutput)

    Wait-ForHttpHealth `
        -Name $ContainerName `
        -Url $HealthUrl `
        -TimeoutSeconds $TimeoutSeconds

    Write-Host "$ContainerName is healthy." `
        -ForegroundColor Green
}

function Wait-ForStableProcessing {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [object[]]$Services,

        [Parameter(Mandatory = $true)]
        [string]$MetricName
    )

    Write-Step (
        "Wait for stable processing: " +
        "$Name ($StabilizationSeconds sec)"
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(
        $TimeoutSeconds
    )
    $stableSince = $null
    $lastCounts = @{}

    foreach ($service in $Services) {
        $lastCounts[$service.Name] =
            Get-PrometheusMetricTotal `
                -BaseUrl $service.BaseUrl `
                -MetricName $MetricName
    }

    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-LoadProcessExited) {
            throw (
                "Full-System load ended before $Name stabilized. " +
                "Increase DurationSeconds."
            )
        }

        $allHealthy = $true
        $allProcessing = $true

        foreach ($service in $Services) {
            try {
                $health = Invoke-WebRequest `
                    -UseBasicParsing `
                    -TimeoutSec 5 `
                    -Uri (
                        $service.BaseUrl.TrimEnd("/") +
                        "/actuator/health"
                    )

                if ([int]$health.StatusCode -ne 200) {
                    $allHealthy = $false
                }

                $currentCount =
                    Get-PrometheusMetricTotal `
                        -BaseUrl $service.BaseUrl `
                        -MetricName $MetricName
                $previousCount =
                    [double]$lastCounts[$service.Name]

                if ($currentCount -le $previousCount) {
                    $allProcessing = $false
                }

                $lastCounts[$service.Name] = $currentCount
            }
            catch {
                $allHealthy = $false
                $allProcessing = $false
            }
        }

        if ($allHealthy -and $allProcessing) {
            if ($null -eq $stableSince) {
                $stableSince = [DateTimeOffset]::UtcNow
            }

            $stableFor = (
                [DateTimeOffset]::UtcNow -
                $stableSince
            ).TotalSeconds

            Write-Host (
                "$Name stable for " +
                "$([Math]::Floor($stableFor)) / " +
                "$StabilizationSeconds seconds"
            )

            if ($stableFor -ge $StabilizationSeconds) {
                Write-Host "$Name is stable." `
                    -ForegroundColor Green
                return
            }
        }
        else {
            $stableSince = $null
            Write-Host (
                "$Name is not stable yet. " +
                "healthy=$allHealthy processing=$allProcessing"
            )
        }

        Start-Sleep -Seconds 2
    }

    throw "Timed out waiting for stable processing: $Name"
}

function ConvertTo-PowerShellLiteral {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    return "'" + $Value.Replace("'", "''") + "'"
}

function Get-LoadProcessError {
    $messages = New-Object System.Collections.Generic.List[string]

    foreach ($file in @(
        $loadStderrFile,
        $loadStdoutFile
    )) {
        if (-not (Test-Path -LiteralPath $file)) {
            continue
        }

        $tail = @(
            Get-ProcessLogLines `
                -Path $file `
                -Tail 20
        )

        if ($tail.Count -gt 0) {
            $messages.Add(
                (
                    $tail |
                        ForEach-Object { [string]$_ }
                ) -join " | "
            )
        }
    }

    if ($messages.Count -eq 0) {
        return "no process output"
    }

    return $messages -join " | "
}

function Get-ProcessLogLines {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [int]$Tail = 120
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return @()
    }

    try {
        $imported = @(
            Import-Clixml `
                -LiteralPath $Path `
                -ErrorAction Stop
        )

        if ($imported.Count -gt 0) {
            return @(
                $imported |
                    ForEach-Object { $_.ToString() } |
                    Select-Object -Last $Tail
            )
        }
    }
    catch {
        # CLIXML이 아니면 일반 텍스트로 읽는다.
    }

    return @(
        Get-Content `
            -LiteralPath $Path `
            -Tail $Tail `
            -ErrorAction SilentlyContinue
    )
}

function Start-FullSystemLoadProcess {
    $command = (
        "`$ProgressPreference = 'SilentlyContinue'; " +
        "Set-Location -LiteralPath " +
        (ConvertTo-PowerShellLiteral $projectRoot) +
        "; & " +
        (ConvertTo-PowerShellLiteral $fullSystemScript) +
        " -DecisionRate $DecisionRate" +
        " -BillingRate $BillingRate" +
        " -DurationSeconds $DurationSeconds" +
        " -ReservationSetupRate $ReservationSetupRate" +
        " -Amount $Amount" +
        " -TimeoutSeconds $TimeoutSeconds" +
        " -LoadBaseUrl " +
        (ConvertTo-PowerShellLiteral "http://pacing-api-lb") +
        " -DecisionErrorRateThreshold " +
        (ConvertTo-PowerShellLiteral "rate==0") +
        " -SkipStart"
    )

    if (-not [string]::IsNullOrWhiteSpace(
        $EnvironmentFile
    )) {
        $command += (
            " -EnvironmentFile " +
            (ConvertTo-PowerShellLiteral $EnvironmentFile)
        )
    }

    $encodedCommand = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($command)
    )

    return Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-OutputFormat",
            "Text",
            "-EncodedCommand",
            $encodedCommand
        ) `
        -RedirectStandardOutput $loadStdoutFile `
        -RedirectStandardError $loadStderrFile `
        -WindowStyle Hidden `
        -PassThru
}

function Test-LoadProcessExited {
    if ($null -eq $loadProcess) {
        return $false
    }

    $loadProcess.Refresh()
    return $loadProcess.HasExited
}

function Write-LoadProcessOutput {
    Write-Host ""
    Write-Host "----- Full-System process stdout -----"

    if (Test-Path -LiteralPath $loadStdoutFile) {
        Get-ProcessLogLines -Path $loadStdoutFile |
            ForEach-Object {
                Write-Host ([string]$_)
            }
    }

    Write-Host "----- Full-System process stderr -----"

    if (Test-Path -LiteralPath $loadStderrFile) {
        Get-ProcessLogLines -Path $loadStderrFile |
            ForEach-Object {
                Write-Host ([string]$_)
            }
    }

    Write-Host "--------------------------------------"
}

function Wait-ForDecisionLoad {
    param(
        [Parameter(Mandatory = $true)]
        [double]$Baseline
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(
        $TimeoutSeconds
    )
    $requiredDelta = [Math]::Min(
        1000,
        [Math]::Max(100, $DecisionRate)
    )

    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-LoadProcessExited) {
            throw (
                "Full-System process exited before load. exit=" +
                $loadProcess.ExitCode +
                " output=" +
                (Get-LoadProcessError)
            )
        }

        try {
            $current = Get-CombinedApiDecisionCount
            $delta = $current - $Baseline

            Write-Host (
                "waiting for decision load: " +
                "delta=$delta / $requiredDelta"
            )

            if ($delta -ge $requiredDelta) {
                return
            }
        }
        catch {
            Write-Host (
                "decision metric temporarily unavailable: " +
                $_.Exception.Message
            )
        }

        Start-Sleep -Seconds 2
    }

    throw "Timed out waiting for active decision load."
}

function Complete-LoadProcess {
    $maximumWaitSeconds = (
        $TimeoutSeconds +
        $DurationSeconds +
        [int][Math]::Ceiling(
            (
                [double]$BillingRate *
                [double]$DurationSeconds
            ) /
            [double]$ReservationSetupRate
        )
    )

    if (-not $loadProcess.WaitForExit(
        $maximumWaitSeconds * 1000
    )) {
        throw (
            "Full-System process did not finish within " +
            "$maximumWaitSeconds seconds."
        )
    }

    # Flush redirected output before reading the files.
    $loadProcess.WaitForExit()
    $loadProcess.Refresh()
    $exitCode = $loadProcess.ExitCode
    Write-LoadProcessOutput

    if ($null -eq $exitCode) {
        throw (
            "Full-System process exit code was not available. " +
            "output=" +
            (Get-LoadProcessError)
        )
    }

    if ($exitCode -ne 0) {
        throw (
            "Full-System process failed. exit=" +
            $exitCode +
            " output=" +
            (Get-LoadProcessError)
        )
    }
}

function Assert-ProcessedAfterRestart {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,

        [Parameter(Mandatory = $true)]
        [string]$MetricName,

        [Parameter(Mandatory = $true)]
        [string]$InstanceName
    )

    $count = Get-PrometheusMetricTotal `
        -BaseUrl $BaseUrl `
        -MetricName $MetricName

    if ($count -le 0) {
        throw (
            "$InstanceName did not process traffic after restart. " +
            "metric=$MetricName count=$count"
        )
    }

    Write-Host "$InstanceName post-restart count: $count"
}

function Write-RollingLogs {
    Write-Host ""
    Write-Host "----- rolling-restart logs -----" `
        -ForegroundColor Yellow

    Push-Location $projectRoot
    try {
        [void](Invoke-DockerCommand `
            -Arguments @(
                "compose",
                "-f",
                "compose.yml",
                "-f",
                "compose.multi-instance.yml",
                "--profile",
                "application",
                "--profile",
                "multi-instance",
                "logs",
                "--tail",
                "150",
                "pacing-api",
                "pacing-api-2",
                "pacing-api-3",
                "pacing-worker",
                "pacing-worker-2",
                "pacing-api-lb"
            ) `
            -StreamOutput)
    }
    finally {
        Pop-Location
    }
}

try {
    Write-Host ""
    Write-Host "============================================"
    Write-Host " Rolling Restart Full-System Test"
    Write-Host "============================================"
    Write-Host "decision rate:          $DecisionRate req/s"
    Write-Host "billing rate:           $BillingRate events/s"
    Write-Host "duration:               $DurationSeconds sec"
    Write-Host "load balancer:          $loadBalancerUrl"
    Write-Host "report directory:       $reportDirectory"

    Write-Step "0. Prepare primary stack"

    if (-not $SkipStart) {
        & (Join-Path $PSScriptRoot "start-local.ps1") `
            -NoBuild:$NoBuild `
            -Monitoring:$Monitoring `
            -TimeoutSeconds $TimeoutSeconds `
            -EnvironmentFile $EnvironmentFile
    }
    else {
        & (Join-Path $PSScriptRoot "wait-for-services.ps1") `
            -TimeoutSeconds $TimeoutSeconds `
            -EnvironmentFile $EnvironmentFile
    }

    Write-Step "1. Ensure multi-instance stack is running"

    Push-Location $projectRoot
    try {
        [void](Invoke-DockerCommand `
            -Arguments @(
                "compose",
                "-f",
                "compose.yml",
                "-f",
                "compose.multi-instance.yml",
                "--profile",
                "application",
                "--profile",
                "multi-instance",
                "up",
                "-d",
                "pacing-api-2",
                "pacing-api-3",
                "pacing-worker-2",
                "pacing-api-lb"
            ) `
            -StreamOutput)
    }
    finally {
        Pop-Location
    }

    Wait-ForHttpHealth `
        -Name "secondary pacing-api" `
        -Url "$secondaryApiUrl/actuator/health" `
        -TimeoutSeconds $TimeoutSeconds
    Wait-ForHttpHealth `
        -Name "tertiary pacing-api" `
        -Url "$tertiaryApiUrl/actuator/health" `
        -TimeoutSeconds $TimeoutSeconds
    Wait-ForHttpHealth `
        -Name "secondary pacing-worker" `
        -Url "$secondaryWorkerUrl/actuator/health" `
        -TimeoutSeconds $TimeoutSeconds
    Wait-ForHttpHealth `
        -Name "pacing-api load balancer" `
        -Url "$loadBalancerUrl/actuator/health" `
        -TimeoutSeconds $TimeoutSeconds

    Set-ApiRouting -Mode "all"

    Start-Sleep -Seconds 5

    Write-Step "2. Start Full-System load in background"
    $decisionBaseline = Get-CombinedApiDecisionCount
    $loadProcess = Start-FullSystemLoadProcess

    Write-Step "3. Wait until decision load is active"
    Wait-ForDecisionLoad -Baseline $decisionBaseline

    Write-Step "4. Restart one instance at a time"

    Set-ApiRouting -Mode "without-primary"
    Restart-And-Wait `
        -ContainerName "pacing-api" `
        -HealthUrl "$primaryApiUrl/actuator/health"
    Set-ApiRouting -Mode "all"
    Wait-ForStableProcessing `
        -Name "API instances after primary restart" `
        -Services $apiServices `
        -MetricName "pacing_api_decision_seconds_count"

    Set-ApiRouting -Mode "without-secondary"
    Restart-And-Wait `
        -ContainerName "pacing-api-2" `
        -HealthUrl "$secondaryApiUrl/actuator/health"
    Set-ApiRouting -Mode "all"
    Wait-ForStableProcessing `
        -Name "API instances after secondary restart" `
        -Services $apiServices `
        -MetricName "pacing_api_decision_seconds_count"

    Set-ApiRouting -Mode "without-tertiary"
    Restart-And-Wait `
        -ContainerName "pacing-api-3" `
        -HealthUrl "$tertiaryApiUrl/actuator/health"
    Set-ApiRouting -Mode "all"
    Wait-ForStableProcessing `
        -Name "API instances after tertiary restart" `
        -Services $apiServices `
        -MetricName "pacing_api_decision_seconds_count"

    Restart-And-Wait `
        -ContainerName "pacing-worker" `
        -HealthUrl "$primaryWorkerUrl/actuator/health"
    Wait-ForStableProcessing `
        -Name "workers after primary restart" `
        -Services $workerServices `
        -MetricName "pacing_worker_billing_seconds_count"

    Restart-And-Wait `
        -ContainerName "pacing-worker-2" `
        -HealthUrl "$secondaryWorkerUrl/actuator/health"
    Wait-ForStableProcessing `
        -Name "workers after secondary restart" `
        -Services $workerServices `
        -MetricName "pacing_worker_billing_seconds_count"

    Write-Step "5. Wait for Full-System verification"
    Complete-LoadProcess

    Write-Step "6. Verify every restarted instance resumed work"

    Assert-ProcessedAfterRestart `
        -BaseUrl $primaryApiUrl `
        -MetricName "pacing_api_decision_seconds_count" `
        -InstanceName "primary API"
    Assert-ProcessedAfterRestart `
        -BaseUrl $secondaryApiUrl `
        -MetricName "pacing_api_decision_seconds_count" `
        -InstanceName "secondary API"
    Assert-ProcessedAfterRestart `
        -BaseUrl $tertiaryApiUrl `
        -MetricName "pacing_api_decision_seconds_count" `
        -InstanceName "tertiary API"
    Assert-ProcessedAfterRestart `
        -BaseUrl $primaryWorkerUrl `
        -MetricName "pacing_worker_billing_seconds_count" `
        -InstanceName "primary worker"
    Assert-ProcessedAfterRestart `
        -BaseUrl $secondaryWorkerUrl `
        -MetricName "pacing_worker_billing_seconds_count" `
        -InstanceName "secondary worker"
}
catch {
    $testFailure = $_

    try {
        Write-RollingLogs
    }
    catch {
        Write-Warning (
            "Could not print rolling-restart logs: " +
            $_.Exception.Message
        )
    }
}
finally {
    try {
        Set-ApiRouting -Mode "all"
    }
    catch {
        Write-Warning (
            "Could not restore all API routes: " +
            $_.Exception.Message
        )
    }

    if ($null -ne $loadProcess) {
        if (-not (Test-LoadProcessExited)) {
            $loadProcess.Kill()
            [void]$loadProcess.WaitForExit(10000)
        }

        $loadProcess.Dispose()
    }
}

if ($null -ne $testFailure) {
    throw $testFailure
}

Write-Host ""
Write-Host "Rolling Restart Full-System Test SUCCESS" `
    -ForegroundColor Green
