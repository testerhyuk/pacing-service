[CmdletBinding()]
param(
    [int]$DecisionRate = 1000,

    [int]$BillingRate = 500,

    [int]$DurationSeconds = 120,

    [int]$ReservationSetupRate = 500,

    [long]$Amount = 10,

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
if ($DurationSeconds -le 0) {
    throw "DurationSeconds must be greater than 0."
}
if ($ReservationSetupRate -le 0) {
    throw "ReservationSetupRate must be greater than 0."
}
if ($Amount -le 0) {
    throw "Amount must be greater than 0."
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
$internalLoadBalancerUrl = "http://pacing-api-lb"
$expectedDecisionRequests = (
    [long]$DecisionRate *
    [long]$DurationSeconds
)
$expectedBillingEvents = (
    [long]$BillingRate *
    [long]$DurationSeconds
)

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

function Get-RestartCount {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ContainerName
    )

    $raw = Invoke-DockerCommand -Arguments @(
        "inspect",
        "--format",
        "{{.RestartCount}}",
        $ContainerName
    )
    $rendered = (
        $raw |
            ForEach-Object { [string]$_ }
    ) -join ""

    return [long]$rendered.Trim()
}

function Get-InstanceSnapshot {
    return [pscustomobject]@{
        primaryApiDecisions = Get-PrometheusMetricTotal `
            -BaseUrl $primaryApiUrl `
            -MetricName "pacing_api_decision_seconds_count"
        secondaryApiDecisions = Get-PrometheusMetricTotal `
            -BaseUrl $secondaryApiUrl `
            -MetricName "pacing_api_decision_seconds_count"
        tertiaryApiDecisions = Get-PrometheusMetricTotal `
            -BaseUrl $tertiaryApiUrl `
            -MetricName "pacing_api_decision_seconds_count"
        primaryWorkerBilling = Get-PrometheusMetricTotal `
            -BaseUrl $primaryWorkerUrl `
            -MetricName "pacing_worker_billing_seconds_count"
        secondaryWorkerBilling = Get-PrometheusMetricTotal `
            -BaseUrl $secondaryWorkerUrl `
            -MetricName "pacing_worker_billing_seconds_count"
        primaryApiRestarts = Get-RestartCount `
            -ContainerName "pacing-api"
        secondaryApiRestarts = Get-RestartCount `
            -ContainerName "pacing-api-2"
        tertiaryApiRestarts = Get-RestartCount `
            -ContainerName "pacing-api-3"
        primaryWorkerRestarts = Get-RestartCount `
            -ContainerName "pacing-worker"
        secondaryWorkerRestarts = Get-RestartCount `
            -ContainerName "pacing-worker-2"
        loadBalancerRestarts = Get-RestartCount `
            -ContainerName "pacing-api-lb"
    }
}

function Assert-PositiveDelta {
    param(
        [Parameter(Mandatory = $true)]
        [double]$Before,

        [Parameter(Mandatory = $true)]
        [double]$After,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $delta = $After - $Before
    if ($delta -le 0) {
        throw "$Name did not process any requests. delta=$delta"
    }

    return $delta
}

function Write-MultiInstanceLogs {
    Write-Host ""
    Write-Host "----- multi-instance logs -----" `
        -ForegroundColor Yellow

    Push-Location (Get-ProjectRoot)
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

$testFailure = $null

try {
    Write-Host ""
    Write-Host "============================================"
    Write-Host " Multi-Instance Full-System Test"
    Write-Host "============================================"
    Write-Host "decision rate:          $DecisionRate req/s"
    Write-Host "billing rate:           $BillingRate events/s"
    Write-Host "duration:               $DurationSeconds sec"
    Write-Host "expected decisions:     $expectedDecisionRequests"
    Write-Host "expected billing:       $expectedBillingEvents"
    Write-Host "load balancer:          $loadBalancerUrl"
    Write-Host "secondary API:          $secondaryApiUrl"
    Write-Host "tertiary API:           $tertiaryApiUrl"
    Write-Host "secondary worker:       $secondaryWorkerUrl"

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

    Write-Step "1. Start additional instances and load balancer"

    Push-Location (Get-ProjectRoot)
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

    Write-Step "2. Wait for every instance"

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

    foreach ($serverName in @(
        "pacing-api",
        "pacing-api-2",
        "pacing-api-3"
    )) {
        Set-HaproxyServerRuntimeState `
            -AdminPort ([int]$loadBalancerAdminPort) `
            -BackendName "pacing_api" `
            -ServerName $serverName `
            -State "ready"
    }

    # Give Kafka enough time to complete the consumer-group rebalance.
    Start-Sleep -Seconds 5

    Write-Step "3. Capture per-instance baseline"
    $before = Get-InstanceSnapshot

    Write-Step "4. Run full-system load through load balancer"

    $fullSystemParameters = @{
        DecisionRate = $DecisionRate
        BillingRate = $BillingRate
        DurationSeconds = $DurationSeconds
        Amount = $Amount
        ReservationSetupRate = $ReservationSetupRate
        TimeoutSeconds = $TimeoutSeconds
        LoadBaseUrl = $internalLoadBalancerUrl
        SkipStart = $true
        EnvironmentFile = $EnvironmentFile
    }

    & (Join-Path `
        $PSScriptRoot `
        "run-full-system-load-test.ps1"
    ) @fullSystemParameters

    Write-Step "5. Verify traffic distribution and restarts"
    $after = Get-InstanceSnapshot

    $primaryApiDelta = Assert-PositiveDelta `
        -Before $before.primaryApiDecisions `
        -After $after.primaryApiDecisions `
        -Name "primary API"
    $secondaryApiDelta = Assert-PositiveDelta `
        -Before $before.secondaryApiDecisions `
        -After $after.secondaryApiDecisions `
        -Name "secondary API"
    $tertiaryApiDelta = Assert-PositiveDelta `
        -Before $before.tertiaryApiDecisions `
        -After $after.tertiaryApiDecisions `
        -Name "tertiary API"
    $primaryWorkerDelta = Assert-PositiveDelta `
        -Before $before.primaryWorkerBilling `
        -After $after.primaryWorkerBilling `
        -Name "primary worker"
    $secondaryWorkerDelta = Assert-PositiveDelta `
        -Before $before.secondaryWorkerBilling `
        -After $after.secondaryWorkerBilling `
        -Name "secondary worker"

    $combinedApiDelta = (
        $primaryApiDelta +
        $secondaryApiDelta +
        $tertiaryApiDelta
    )
    $combinedWorkerDelta = (
        $primaryWorkerDelta +
        $secondaryWorkerDelta
    )

    if ($combinedApiDelta -lt $expectedDecisionRequests) {
        throw (
            "Combined API decision count is too small. " +
            "expectedAtLeast=$expectedDecisionRequests " +
            "actual=$combinedApiDelta"
        )
    }
    if ($combinedWorkerDelta -lt $expectedBillingEvents) {
        throw (
            "Combined worker billing count is too small. " +
            "expectedAtLeast=$expectedBillingEvents " +
            "actual=$combinedWorkerDelta"
        )
    }

    foreach ($container in @(
        @(
            "pacing-api",
            $before.primaryApiRestarts,
            $after.primaryApiRestarts
        ),
        @(
            "pacing-api-2",
            $before.secondaryApiRestarts,
            $after.secondaryApiRestarts
        ),
        @(
            "pacing-api-3",
            $before.tertiaryApiRestarts,
            $after.tertiaryApiRestarts
        ),
        @(
            "pacing-worker",
            $before.primaryWorkerRestarts,
            $after.primaryWorkerRestarts
        ),
        @(
            "pacing-worker-2",
            $before.secondaryWorkerRestarts,
            $after.secondaryWorkerRestarts
        ),
        @(
            "pacing-api-lb",
            $before.loadBalancerRestarts,
            $after.loadBalancerRestarts
        )
    )) {
        $name = [string]$container[0]
        $restartDelta = (
            [long]$container[2] -
            [long]$container[1]
        )

        Assert-Equal `
            -Expected 0L `
            -Actual $restartDelta `
            -Message "$name restarted during the test."
    }

    Write-Host ""
    Write-Host "============================================"
    Write-Host " Multi-Instance Result"
    Write-Host "============================================"
    Write-Host "primary API decisions:     $primaryApiDelta"
    Write-Host "secondary API decisions:   $secondaryApiDelta"
    Write-Host "tertiary API decisions:    $tertiaryApiDelta"
    Write-Host "combined API decisions:    $combinedApiDelta"
    Write-Host "primary worker events:     $primaryWorkerDelta"
    Write-Host "secondary worker events:   $secondaryWorkerDelta"
    Write-Host "combined worker events:    $combinedWorkerDelta"
    Write-Host "container restarts:        0"
}
catch {
    $testFailure = $_

    try {
        Write-MultiInstanceLogs
    }
    catch {
        Write-Warning (
            "Could not print multi-instance logs: " +
            $_.Exception.Message
        )
    }
}

if ($null -ne $testFailure) {
    throw $testFailure
}

Write-Host ""
Write-Host "Multi-Instance Full-System Test SUCCESS" `
    -ForegroundColor Green
