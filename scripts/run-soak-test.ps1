[CmdletBinding()]
param(
    [int]$DecisionRate = 800,

    [int]$BillingRate = 200,

    [int]$DurationSeconds = 1800,

    [int]$ReservationTtlSeconds = 900,

    [int]$ReservationExpirySafetySeconds = 120,

    [int]$ReservationSetupRate = 400,

    [long]$Amount = 10,

    [int]$SampleIntervalSeconds = 30,

    [int]$DecisionPreAllocatedVUs = 200,

    [int]$DecisionMaxVUs = 300,

    [int]$ReservationPreAllocatedVUs = 100,

    [int]$ReservationMaxVUs = 200,

    [int]$TimeoutSeconds = 600,

    [switch]$SkipStart,

    [switch]$NoBuild,

    [switch]$Monitoring,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ($DecisionRate -le 0) {
    throw "DecisionRate는 0보다 커야 합니다."
}
if ($BillingRate -le 0) {
    throw "BillingRate는 0보다 커야 합니다."
}
if ($DurationSeconds -le 0) {
    throw "DurationSeconds는 0보다 커야 합니다."
}
if ($ReservationTtlSeconds -le 0) {
    throw "ReservationTtlSeconds는 0보다 커야 합니다."
}
if (
    $ReservationExpirySafetySeconds -lt 0 -or
    $ReservationExpirySafetySeconds -ge $ReservationTtlSeconds
) {
    throw (
        "ReservationExpirySafetySeconds는 0 이상이고 " +
        "ReservationTtlSeconds보다 작아야 합니다."
    )
}
if ($ReservationSetupRate -le 0) {
    throw "ReservationSetupRate는 0보다 커야 합니다."
}
if ($Amount -le 0) {
    throw "Amount는 0보다 커야 합니다."
}
if ($SampleIntervalSeconds -lt 5) {
    throw "SampleIntervalSeconds는 5초 이상이어야 합니다."
}
if ($TimeoutSeconds -le 0) {
    throw "TimeoutSeconds는 0보다 커야 합니다."
}

$totalBillingEvents = [long]$BillingRate * [long]$DurationSeconds
$reservationSetupSeconds = [Math]::Ceiling(
    [double]$totalBillingEvents / [double]$ReservationSetupRate
)
$usableReservationSeconds = (
    $ReservationTtlSeconds -
    $ReservationExpirySafetySeconds
)
$maxRoundDurationSeconds = [int][Math]::Floor(
    [double]$usableReservationSeconds *
    [double]$ReservationSetupRate /
    (
        [double]$ReservationSetupRate +
        [double]$BillingRate
    )
)

if ($maxRoundDurationSeconds -le 0) {
    throw (
        "현재 예약 TTL과 부하율로는 안전한 테스트 구간을 " +
        "계산할 수 없습니다."
    )
}

$roundCount = [int][Math]::Ceiling(
    [double]$DurationSeconds /
    [double]$maxRoundDurationSeconds
)
$estimatedTotalSeconds = (
    [long]$reservationSetupSeconds +
    [long]$DurationSeconds
)

$runId = New-TestIdentifier -Prefix "soak"
$reportDirectory = Join-Path `
    (Get-ProjectRoot) `
    "build\reports\soak\$runId"
$metricsFile = Join-Path $reportDirectory "metrics.csv"
$stopSignalFile = Join-Path $reportDirectory "monitor.stop"

[void](New-Item `
    -ItemType Directory `
    -Path $reportDirectory `
    -Force)

$redisPassword = Get-EnvironmentValue `
    -Name "PACING_REDIS_PASSWORD" `
    -DefaultValue "pacing"

$apiBaseUrl = Get-ApiBaseUrl
$workerBaseUrl = Get-WorkerBaseUrl
$monitorJob = $null
$loadFailure = $null
$summaryFailure = $null

function Start-SoakMonitor {
    return Start-Job `
        -ArgumentList @(
            $metricsFile,
            $stopSignalFile,
            $SampleIntervalSeconds,
            $apiBaseUrl,
            $workerBaseUrl,
            $redisPassword
        ) `
        -ScriptBlock {
            param(
                [string]$MetricsFile,
                [string]$StopSignalFile,
                [int]$SampleIntervalSeconds,
                [string]$ApiBaseUrl,
                [string]$WorkerBaseUrl,
                [string]$RedisPassword
            )

            Set-StrictMode -Version Latest
            $ErrorActionPreference = "Stop"

            function Get-PrometheusText {
                param(
                    [Parameter(Mandatory = $true)]
                    [string]$BaseUrl
                )

                $response = Invoke-WebRequest `
                    -UseBasicParsing `
                    -TimeoutSec 10 `
                    -Uri (
                        $BaseUrl.TrimEnd("/") +
                        "/actuator/prometheus"
                    )

                return [string]$response.Content
            }

            function Get-PrometheusMetric {
                param(
                    [Parameter(Mandatory = $true)]
                    [string]$Text,

                    [Parameter(Mandatory = $true)]
                    [string]$MetricName,

                    [string]$RequiredLabel
                )

                [double]$total = 0
                $matched = $false
                $escapedName = [regex]::Escape($MetricName)

                foreach ($line in ($Text -split "`r?`n")) {
                    if ($line -notmatch (
                        "^$escapedName" +
                        '(?:\{(?<labels>[^}]*)\})?\s+' +
                        '(?<value>[-+0-9.eE]+)\s*$'
                    )) {
                        continue
                    }

                    $labels = if ($Matches.ContainsKey("labels")) {
                        [string]$Matches["labels"]
                    }
                    else {
                        ""
                    }

                    if (-not [string]::IsNullOrWhiteSpace(
                        $RequiredLabel
                    )) {
                        if (-not $labels.Contains($RequiredLabel)) {
                            continue
                        }
                    }

                    $total += [double]::Parse(
                        $Matches["value"],
                        [Globalization.CultureInfo]::InvariantCulture
                    )
                    $matched = $true
                }

                if (-not $matched) {
                    return $null
                }

                return $total
            }

            function Get-RestartCount {
                param(
                    [Parameter(Mandatory = $true)]
                    [string]$ContainerName
                )

                $raw = & docker inspect `
                    --format "{{.RestartCount}}" `
                    $ContainerName 2>$null

                if ($LASTEXITCODE -ne 0) {
                    throw "컨테이너 재시작 횟수 조회 실패: $ContainerName"
                }

                return [long](($raw | Out-String).Trim())
            }

            function Get-RedisMetrics {
                $memoryOutput = & docker exec `
                    pacing-redis `
                    redis-cli `
                    --raw `
                    --no-auth-warning `
                    -a $RedisPassword `
                    INFO memory 2>$null

                if ($LASTEXITCODE -ne 0) {
                    throw "Redis memory 조회 실패"
                }

                $memoryText = ($memoryOutput | Out-String)
                if ($memoryText -notmatch '(?m)^used_memory:(\d+)\r?$') {
                    throw "Redis used_memory 값을 찾을 수 없습니다."
                }

                [long]$usedMemory = $Matches[1]

                $dbSizeOutput = & docker exec `
                    pacing-redis `
                    redis-cli `
                    --raw `
                    --no-auth-warning `
                    -a $RedisPassword `
                    DBSIZE 2>$null

                if ($LASTEXITCODE -ne 0) {
                    throw "Redis DBSIZE 조회 실패"
                }

                [long]$keyCount = (
                    ($dbSizeOutput | Out-String).Trim()
                )

                return [pscustomobject]@{
                    usedMemoryBytes = $usedMemory
                    keyCount = $keyCount
                }
            }

            function Get-KafkaLag {
                $output = & docker exec `
                    pacing-kafka `
                    /opt/kafka/bin/kafka-consumer-groups.sh `
                    --bootstrap-server localhost:9092 `
                    --describe `
                    --group pacing-worker-billing-v1 2>$null

                if ($LASTEXITCODE -ne 0) {
                    throw "Kafka consumer lag 조회 실패"
                }

                [long]$totalLag = 0
                $matched = $false

                foreach ($lineObject in $output) {
                    $line = ([string]$lineObject).Trim()

                    if (
                        [string]::IsNullOrWhiteSpace($line) -or
                        $line.StartsWith("GROUP") -or
                        $line.StartsWith("Consumer group")
                    ) {
                        continue
                    }

                    $parts = $line -split "\s+"

                    if (
                        $parts.Count -lt 6 -or
                        $parts[0] -ne "pacing-worker-billing-v1" -or
                        $parts[1] -ne "billing.events.v1"
                    ) {
                        continue
                    }

                    [long]$lag = 0
                    if (-not [long]::TryParse(
                        $parts[5],
                        [ref]$lag
                    )) {
                        continue
                    }

                    $matched = $true
                    $totalLag += $lag
                }

                if (-not $matched) {
                    return 0L
                }

                return $totalLag
            }

            function Get-ContainerStats {
                $result = @{}
                $output = & docker stats `
                    --no-stream `
                    --format "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}" `
                    pacing-api `
                    pacing-worker `
                    pacing-redis `
                    pacing-postgres `
                    pacing-kafka 2>$null

                if ($LASTEXITCODE -ne 0) {
                    throw "Docker stats 조회 실패"
                }

                foreach ($lineObject in $output) {
                    $parts = ([string]$lineObject) -split "\|", 3

                    if ($parts.Count -ne 3) {
                        continue
                    }

                    $result[$parts[0]] = [pscustomobject]@{
                        cpu = $parts[1]
                        memory = $parts[2]
                    }
                }

                return $result
            }

            while ($true) {
                $stopRequestedBeforeSample = Test-Path `
                    -LiteralPath $StopSignalFile
                $sampledAt = [DateTimeOffset]::UtcNow.ToString("o")
                $sampleError = ""

                $row = [ordered]@{
                    sampledAt = $sampledAt
                    apiHeapBytes = $null
                    apiLiveDataBytes = $null
                    apiThreads = $null
                    apiHikariActive = $null
                    apiHikariPending = $null
                    apiHikariMax = $null
                    apiProcessCpu = $null
                    apiSystemCpu = $null
                    workerHeapBytes = $null
                    workerLiveDataBytes = $null
                    workerThreads = $null
                    workerHikariActive = $null
                    workerHikariPending = $null
                    workerHikariMax = $null
                    workerProcessCpu = $null
                    workerSystemCpu = $null
                    redisUsedMemoryBytes = $null
                    redisKeyCount = $null
                    kafkaLag = $null
                    apiRestartCount = $null
                    workerRestartCount = $null
                    redisRestartCount = $null
                    postgresRestartCount = $null
                    kafkaRestartCount = $null
                    apiContainerCpu = $null
                    apiContainerMemory = $null
                    workerContainerCpu = $null
                    workerContainerMemory = $null
                    redisContainerCpu = $null
                    redisContainerMemory = $null
                    postgresContainerCpu = $null
                    postgresContainerMemory = $null
                    kafkaContainerCpu = $null
                    kafkaContainerMemory = $null
                    error = ""
                }

                try {
                    $apiText = Get-PrometheusText `
                        -BaseUrl $ApiBaseUrl
                    $workerText = Get-PrometheusText `
                        -BaseUrl $WorkerBaseUrl

                    $row.apiHeapBytes = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "jvm_memory_used_bytes" `
                        -RequiredLabel 'area="heap"'
                    $row.apiLiveDataBytes = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "jvm_gc_live_data_size_bytes"
                    $row.apiThreads = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "jvm_threads_live_threads"
                    $row.apiHikariActive = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "hikaricp_connections_active"
                    $row.apiHikariPending = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "hikaricp_connections_pending"
                    $row.apiHikariMax = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "hikaricp_connections_max"
                    $row.apiProcessCpu = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "process_cpu_usage"
                    $row.apiSystemCpu = Get-PrometheusMetric `
                        -Text $apiText `
                        -MetricName "system_cpu_usage"

                    $row.workerHeapBytes = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "jvm_memory_used_bytes" `
                        -RequiredLabel 'area="heap"'
                    $row.workerLiveDataBytes = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "jvm_gc_live_data_size_bytes"
                    $row.workerThreads = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "jvm_threads_live_threads"
                    $row.workerHikariActive = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "hikaricp_connections_active"
                    $row.workerHikariPending = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "hikaricp_connections_pending"
                    $row.workerHikariMax = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "hikaricp_connections_max"
                    $row.workerProcessCpu = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "process_cpu_usage"
                    $row.workerSystemCpu = Get-PrometheusMetric `
                        -Text $workerText `
                        -MetricName "system_cpu_usage"

                    $redisMetrics = Get-RedisMetrics
                    $row.redisUsedMemoryBytes = (
                        $redisMetrics.usedMemoryBytes
                    )
                    $row.redisKeyCount = $redisMetrics.keyCount
                    $row.kafkaLag = Get-KafkaLag

                    $row.apiRestartCount = Get-RestartCount `
                        -ContainerName "pacing-api"
                    $row.workerRestartCount = Get-RestartCount `
                        -ContainerName "pacing-worker"
                    $row.redisRestartCount = Get-RestartCount `
                        -ContainerName "pacing-redis"
                    $row.postgresRestartCount = Get-RestartCount `
                        -ContainerName "pacing-postgres"
                    $row.kafkaRestartCount = Get-RestartCount `
                        -ContainerName "pacing-kafka"

                    $containerStats = Get-ContainerStats

                    foreach ($mapping in @(
                        @("pacing-api", "api"),
                        @("pacing-worker", "worker"),
                        @("pacing-redis", "redis"),
                        @("pacing-postgres", "postgres"),
                        @("pacing-kafka", "kafka")
                    )) {
                        $containerName = $mapping[0]
                        $propertyPrefix = $mapping[1]

                        if (-not $containerStats.ContainsKey(
                            $containerName
                        )) {
                            continue
                        }

                        $row[
                            "${propertyPrefix}ContainerCpu"
                        ] = $containerStats[$containerName].cpu
                        $row[
                            "${propertyPrefix}ContainerMemory"
                        ] = $containerStats[$containerName].memory
                    }
                }
                catch {
                    $sampleError = $_.Exception.Message.Replace(
                        [Environment]::NewLine,
                        " "
                    )
                    $row.error = $sampleError
                }

                [pscustomobject]$row |
                    Export-Csv `
                        -LiteralPath $MetricsFile `
                        -NoTypeInformation `
                        -Append `
                        -Encoding UTF8

                # 종료 요청 이후의 최종 상태를 한 번 더 기록한다.
                if ($stopRequestedBeforeSample) {
                    break
                }

                Start-Sleep -Seconds $SampleIntervalSeconds
            }
        }
}

function Stop-SoakMonitor {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Job
    )

    [System.IO.File]::WriteAllText(
        $stopSignalFile,
        "stop"
    )

    $waitSeconds = ($SampleIntervalSeconds * 2) + 30
    [void](Wait-Job -Job $Job -Timeout $waitSeconds)

    if ($Job.State -eq "Running") {
        Stop-Job -Job $Job
        [void](Wait-Job -Job $Job -Timeout 10)
    }

    $jobOutput = Receive-Job -Job $Job
    foreach ($line in $jobOutput) {
        Write-Host ([string]$line)
    }

    $jobState = $Job.State
    Remove-Job -Job $Job -Force

    if (Test-Path -LiteralPath $stopSignalFile) {
        Remove-Item -LiteralPath $stopSignalFile -Force
    }

    if ($jobState -eq "Failed") {
        throw "Soak 모니터링 작업이 실패했습니다."
    }
}

function Get-NumericValues {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Rows,

        [Parameter(Mandatory = $true)]
        [string]$Property
    )

    $values = New-Object System.Collections.Generic.List[double]

    foreach ($row in $Rows) {
        $raw = [string]$row.$Property

        if ([string]::IsNullOrWhiteSpace($raw)) {
            continue
        }

        [double]$value = 0
        if ([double]::TryParse(
            $raw,
            [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$value
        )) {
            $values.Add($value)
        }
    }

    return $values.ToArray()
}

function Get-MaxValue {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Rows,

        [Parameter(Mandatory = $true)]
        [string]$Property
    )

    $values = Get-NumericValues -Rows $Rows -Property $Property

    if ($values.Count -eq 0) {
        return 0.0
    }

    return (
        $values |
            Measure-Object -Maximum
    ).Maximum
}

function Convert-BytesToMiB {
    param(
        [double]$Bytes
    )

    return [Math]::Round(
        $Bytes / 1MB,
        2
    )
}

function Write-SoakSummary {
    if (-not (Test-Path -LiteralPath $metricsFile)) {
        throw "Soak 메트릭 파일이 생성되지 않았습니다."
    }

    $rows = @(Import-Csv -LiteralPath $metricsFile)

    if ($rows.Count -eq 0) {
        throw "Soak 메트릭 표본이 없습니다."
    }

    $errorSamples = @(
        $rows |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace($_.error)
            }
    ).Count
    $validRows = @(
        $rows |
            Where-Object {
                [string]::IsNullOrWhiteSpace($_.error) -and
                -not [string]::IsNullOrWhiteSpace(
                    $_.kafkaLag
                ) -and
                -not [string]::IsNullOrWhiteSpace(
                    $_.apiRestartCount
                )
            }
    )

    if ($validRows.Count -lt 2) {
        throw "유효한 Soak 메트릭 표본이 2개 미만입니다."
    }

    $first = $validRows[0]
    $last = $validRows[$validRows.Count - 1]

    $apiRestartDelta = (
        [long]$last.apiRestartCount -
        [long]$first.apiRestartCount
    )
    $workerRestartDelta = (
        [long]$last.workerRestartCount -
        [long]$first.workerRestartCount
    )
    $redisRestartDelta = (
        [long]$last.redisRestartCount -
        [long]$first.redisRestartCount
    )
    $postgresRestartDelta = (
        [long]$last.postgresRestartCount -
        [long]$first.postgresRestartCount
    )
    $kafkaRestartDelta = (
        [long]$last.kafkaRestartCount -
        [long]$first.kafkaRestartCount
    )

    Write-Host ""
    Write-Host "============================================"
    Write-Host " Soak Monitoring Summary"
    Write-Host "============================================"
    Write-Host "samples:                 $($rows.Count)"
    Write-Host "monitor errors:          $errorSamples"
    Write-Host (
        "API heap MiB:            start=" +
        (Convert-BytesToMiB ([double]$first.apiHeapBytes)) +
        " end=" +
        (Convert-BytesToMiB ([double]$last.apiHeapBytes)) +
        " max=" +
        (Convert-BytesToMiB (
             Get-MaxValue -Rows $validRows -Property "apiHeapBytes"
        ))
    )
    Write-Host (
        "Worker heap MiB:         start=" +
        (Convert-BytesToMiB ([double]$first.workerHeapBytes)) +
        " end=" +
        (Convert-BytesToMiB ([double]$last.workerHeapBytes)) +
        " max=" +
        (Convert-BytesToMiB (
             Get-MaxValue -Rows $validRows -Property "workerHeapBytes"
        ))
    )
    Write-Host (
        "API threads:             start=$($first.apiThreads) " +
        "end=$($last.apiThreads) " +
        "max=" +
        (Get-MaxValue -Rows $validRows -Property "apiThreads")
    )
    Write-Host (
        "Worker threads:          start=$($first.workerThreads) " +
        "end=$($last.workerThreads) " +
        "max=" +
        (Get-MaxValue -Rows $validRows -Property "workerThreads")
    )
    Write-Host (
        "API Hikari pending max:  " +
        (Get-MaxValue `
            -Rows $validRows `
            -Property "apiHikariPending")
    )
    Write-Host (
        "Worker Hikari pending:   " +
        (Get-MaxValue `
            -Rows $validRows `
            -Property "workerHikariPending")
    )
    Write-Host (
        "Redis memory MiB:        start=" +
        (Convert-BytesToMiB (
            [double]$first.redisUsedMemoryBytes
        )) +
        " end=" +
        (Convert-BytesToMiB (
            [double]$last.redisUsedMemoryBytes
        )) +
        " max=" +
        (Convert-BytesToMiB (
            Get-MaxValue `
                -Rows $validRows `
                -Property "redisUsedMemoryBytes"
        ))
    )
    Write-Host (
        "Redis keys:              start=$($first.redisKeyCount) " +
        "end=$($last.redisKeyCount) " +
        "max=" +
        (Get-MaxValue -Rows $validRows -Property "redisKeyCount")
    )
    Write-Host (
        "Kafka lag:               end=$($last.kafkaLag) " +
        "max=" +
        (Get-MaxValue -Rows $validRows -Property "kafkaLag")
    )
    Write-Host (
        "Container restarts:      api=$apiRestartDelta " +
        "worker=$workerRestartDelta " +
        "redis=$redisRestartDelta " +
        "postgres=$postgresRestartDelta " +
        "kafka=$kafkaRestartDelta"
    )
    Write-Host "metrics CSV:             $metricsFile"

    if ($errorSamples -gt 3) {
        throw (
            "모니터링 표본 오류가 허용 범위를 초과했습니다: " +
            "$errorSamples"
        )
    }

    if ([long]$last.kafkaLag -ne 0L) {
        throw "테스트 종료 후 Kafka Lag이 0이 아닙니다."
    }

    if (
        $apiRestartDelta -ne 0 -or
        $workerRestartDelta -ne 0 -or
        $redisRestartDelta -ne 0 -or
        $postgresRestartDelta -ne 0 -or
        $kafkaRestartDelta -ne 0
    ) {
        throw "Soak 테스트 중 컨테이너 재시작이 발생했습니다."
    }
}

try {
    Write-Host ""
    Write-Host "============================================"
    Write-Host " Long-Running Soak Test"
    Write-Host "============================================"
    Write-Host "decision rate:           $DecisionRate req/s"
    Write-Host "billing rate:            $BillingRate events/s"
    Write-Host "load duration:           $DurationSeconds sec"
    Write-Host "billing events:          $totalBillingEvents"
    Write-Host "reservation setup rate:  $ReservationSetupRate req/s"
    Write-Host "reservation TTL:         $ReservationTtlSeconds sec"
    Write-Host (
        "reservation safety:      " +
        "$ReservationExpirySafetySeconds sec"
    )
    Write-Host (
        "maximum round duration:  " +
        "$maxRoundDurationSeconds sec"
    )
    Write-Host "test rounds:             $roundCount"
    Write-Host "estimated setup time:    $reservationSetupSeconds sec"
    Write-Host "estimated total time:    $estimatedTotalSeconds sec"
    Write-Host "sample interval:         $SampleIntervalSeconds sec"
    Write-Host "report directory:        $reportDirectory"

    Write-Step "0. 테스트 환경 준비"

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

    Write-Step "1. 장시간 운영 지표 수집 시작"

    $monitorJob = Start-SoakMonitor

    Wait-Until `
        -TimeoutSeconds 30 `
        -IntervalMilliseconds 500 `
        -FailureMessage "첫 번째 Soak 메트릭 표본이 생성되지 않았습니다." `
        -Condition {
            if (-not (Test-Path -LiteralPath $metricsFile)) {
                return $false
            }

            return @(
                Import-Csv -LiteralPath $metricsFile
            ).Count -ge 1
        }

    Write-Step "2. Full-System 장시간 부하 실행"

    $remainingDurationSeconds = $DurationSeconds

    for (
        $round = 1;
        $round -le $roundCount;
        $round++
    ) {
        $roundDurationSeconds = [Math]::Min(
            $maxRoundDurationSeconds,
            $remainingDurationSeconds
        )
        $roundReservationSetupSeconds = [Math]::Ceiling(
            [double](
                [long]$BillingRate *
                [long]$roundDurationSeconds
            ) /
            [double]$ReservationSetupRate
        )
        $maximumReservationAgeSeconds = (
            $roundReservationSetupSeconds +
            $roundDurationSeconds
        )

        Write-Host ""
        Write-Host (
            "Soak round ${round}/${roundCount}: " +
            "load=$roundDurationSeconds sec, " +
            "setup~=$roundReservationSetupSeconds sec, " +
            "max reservation age~=$maximumReservationAgeSeconds sec"
        ) -ForegroundColor Cyan

        $fullSystemParameters = @{
            DecisionRate = $DecisionRate
            BillingRate = $BillingRate
            DurationSeconds = $roundDurationSeconds
            Amount = $Amount
            ReservationSetupRate = $ReservationSetupRate
            DecisionPreAllocatedVUs = $DecisionPreAllocatedVUs
            DecisionMaxVUs = $DecisionMaxVUs
            ReservationPreAllocatedVUs =
                    $ReservationPreAllocatedVUs
            ReservationMaxVUs = $ReservationMaxVUs
            TimeoutSeconds = $TimeoutSeconds
            SkipStart = $true
            EnvironmentFile = $EnvironmentFile
        }

        & (Join-Path `
            $PSScriptRoot `
            "run-full-system-load-test.ps1"
        ) @fullSystemParameters

        $remainingDurationSeconds -= $roundDurationSeconds
    }
}
catch {
    $loadFailure = $_
}
finally {
    if ($null -ne $monitorJob) {
        try {
            Stop-SoakMonitor -Job $monitorJob
        }
        catch {
            if ($null -eq $loadFailure) {
                $loadFailure = $_
            }
            else {
                Write-Warning (
                    "Soak 모니터 종료 중 추가 오류: " +
                    $_.Exception.Message
                )
            }
        }
    }
}

try {
    Write-SoakSummary
}
catch {
    $summaryFailure = $_
}

if ($null -ne $loadFailure) {
    if ($null -ne $summaryFailure) {
        Write-Warning (
            "Soak 요약 생성 중 추가 오류: " +
            $summaryFailure.Exception.Message
        )
    }

    throw $loadFailure
}

if ($null -ne $summaryFailure) {
    throw $summaryFailure
}

Write-Host ""
Write-Host "Long-Running Soak Test SUCCESS" `
    -ForegroundColor Green
Write-Host "상세 지표: $metricsFile"
