[CmdletBinding()]
param(
    [int]$DecisionRate = 1000,
    [int]$BillingRate = 100,
    [int]$DurationSeconds = 120,
    [long]$Amount = 10,
    [int]$ReservationSetupRate = 500,
    [int]$DecisionPreAllocatedVUs = 200,
    [int]$DecisionMaxVUs = 300,
    [int]$ReservationPreAllocatedVUs = 100,
    [int]$ReservationMaxVUs = 200,
    [int]$TimeoutSeconds = 300,
    [string]$LoadBaseUrl = "http://pacing-api:8080",
    [string]$DecisionErrorRateThreshold = "rate<0.01",
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
if ($Amount -le 0) {
    throw "Amount must be greater than 0."
}
if ($ReservationSetupRate -le 0) {
    throw "ReservationSetupRate must be greater than 0."
}
if ($DecisionPreAllocatedVUs -le 0 -or $DecisionMaxVUs -lt $DecisionPreAllocatedVUs) {
    throw "Decision VU settings are invalid."
}
if ($ReservationPreAllocatedVUs -le 0 -or $ReservationMaxVUs -lt $ReservationPreAllocatedVUs) {
    throw "Reservation VU settings are invalid."
}
if ([string]::IsNullOrWhiteSpace($LoadBaseUrl)) {
    throw "LoadBaseUrl must not be blank."
}
if ([string]::IsNullOrWhiteSpace(
    $DecisionErrorRateThreshold
)) {
    throw "DecisionErrorRateThreshold must not be blank."
}

$LoadBaseUrl = $LoadBaseUrl.TrimEnd("/")

$totalBillingEvents = [long]$BillingRate * [long]$DurationSeconds
$expectedSpend = $totalBillingEvents * $Amount
$testBudget = $expectedSpend * 4L

if ($totalBillingEvents -le 0 -or $expectedSpend -le 0 -or $testBudget -le 0) {
    throw "Calculated test values are invalid or overflowed."
}

$campaignId = New-TestIdentifier -Prefix "full-load-campaign"
$reservationPrefix = New-TestIdentifier -Prefix "full-load-reservation"
$billingPrefix = New-TestIdentifier -Prefix "full-load-billing"
$decisionContainerName = "pacing-k6-full-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)

$topic = "billing.events.v1"
$consumerGroup = "pacing-worker-billing-v1"
$loadScriptDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "load"))
$reservationSetupScript = Join-Path $loadScriptDirectory "full-system-reservation-setup.js"
$decisionScript = Join-Path $loadScriptDirectory "pacing-decision.js"
$k6Image = Get-EnvironmentValue -Name "PACING_K6_IMAGE" -DefaultValue "grafana/k6:latest"

if (-not (Test-Path -LiteralPath $reservationSetupScript)) {
    throw "Missing file: $reservationSetupScript"
}
if (-not (Test-Path -LiteralPath $decisionScript)) {
    throw "Missing file: $decisionScript"
}

function Convert-ToLongValue {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is empty."
    }

    [long]$parsed = 0
    if (-not [long]::TryParse($Value.Trim(), [ref]$parsed)) {
        throw "$Name is not a long value: $Value"
    }

    return $parsed
}

function Get-DbLong {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $raw = Invoke-PostgresScalar -Sql $Sql
    return Convert-ToLongValue -Value $raw -Name $Name
}

function Get-ApplicationDockerNetwork {
    $raw = Invoke-DockerCommand -Arguments @(
        "inspect",
        "pacing-api",
        "--format",
        "{{json .NetworkSettings.Networks}}"
    )

    $jsonText = ($raw | ForEach-Object { [string]$_ }) -join ""
    if ([string]::IsNullOrWhiteSpace($jsonText)) {
        throw "Could not read pacing-api Docker networks."
    }

    $networkObject = $jsonText | ConvertFrom-Json
    $networkNames = @($networkObject.PSObject.Properties | ForEach-Object { $_.Name })

    if ($networkNames.Count -eq 0) {
        throw "pacing-api is not connected to a Docker network."
    }

    return [string]$networkNames[0]
}

function Get-RedisBudgetLong {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Key,

        [Parameter(Mandatory = $true)]
        [string]$Field
    )

    $raw = Invoke-RedisScalar -RedisArguments @("HGET", $Key, $Field)
    return Convert-ToLongValue -Value $raw -Name "Redis $Key/$Field"
}

function Get-KafkaConsumerLag {
    $output = Invoke-DockerCommand -Arguments @(
        "exec",
        "pacing-kafka",
        "/opt/kafka/bin/kafka-consumer-groups.sh",
        "--bootstrap-server",
        "localhost:9092",
        "--describe",
        "--group",
        $consumerGroup
    )

    [long]$totalLag = 0
    $matched = $false

    foreach ($lineObject in $output) {
        $line = ([string]$lineObject).Trim()
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line.StartsWith("GROUP")) {
            continue
        }
        if ($line.StartsWith("Consumer group")) {
            continue
        }

        $parts = $line -split "\s+"
        if ($parts.Count -lt 6) {
            continue
        }
        if ($parts[0] -ne $consumerGroup) {
            continue
        }
        if ($parts[1] -ne $topic) {
            continue
        }

        [long]$lag = 0
        if (-not [long]::TryParse($parts[5], [ref]$lag)) {
            continue
        }

        $matched = $true
        $totalLag += $lag
    }

    if (-not $matched) {
        throw "Kafka lag row not found for group=$consumerGroup topic=$topic"
    }

    return $totalLag
}

function Start-DecisionLoadContainer {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerNetwork
    )

    $secret = Get-HmacSecret -ClientId "ad-server"

    $arguments = @(
        "run",
        "-d",
        "--name",
        $decisionContainerName,
        "--network",
        $DockerNetwork,
        "-e",
        "BASE_URL=$LoadBaseUrl",
        "-e",
        "CLIENT_ID=ad-server",
        "-e",
        "SECRET=$secret",
        "-e",
        "CAMPAIGN_ID=$campaignId",
        "-e",
        "RATE=$DecisionRate",
        "-e",
        "DURATION=${DurationSeconds}s",
        "-e",
        "PRE_ALLOCATED_VUS=$DecisionPreAllocatedVUs",
        "-e",
        "MAX_VUS=$DecisionMaxVUs",
        "-e",
        "ERROR_RATE_THRESHOLD=$DecisionErrorRateThreshold",
        "-v",
        "${loadScriptDirectory}:/scripts:ro",
        $k6Image,
        "run",
        "/scripts/pacing-decision.js"
    )

    $containerId = Invoke-DockerCommand -Arguments $arguments
    $renderedId = ($containerId | ForEach-Object { [string]$_ }) -join ""

    if ([string]::IsNullOrWhiteSpace($renderedId)) {
        throw "Failed to start decision k6 container."
    }

    Write-Host "Decision k6 container started: $decisionContainerName"
}

function Wait-DecisionLoadContainer {
    $waitOutput = Invoke-DockerCommand -Arguments @("wait", $decisionContainerName)
    $exitText = (($waitOutput | ForEach-Object { [string]$_ }) -join "").Trim()

    [int]$containerExitCode = -1
    if (-not [int]::TryParse($exitText, [ref]$containerExitCode)) {
        throw "Could not parse decision k6 exit code: $exitText"
    }

    Write-Host ""
    Write-Host "----- decision k6 output -----"
    $logs = Invoke-DockerCommand -Arguments @("logs", $decisionContainerName)
    foreach ($line in $logs) {
        Write-Host ([string]$line)
    }
    Write-Host "------------------------------"
    Write-Host "decision k6 exit code: $containerExitCode"

    return $containerExitCode
}

function Publish-BillingLoad {
    param(
        [Parameter(Mandatory = $true)]
        [long]$EventCount
    )

    Write-Step "Publish billing events"
    Write-Host "rate:   $BillingRate events/s"
    Write-Host "events: $EventCount"
    Write-Host "amount: $Amount"

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "docker"
    $startInfo.Arguments = "exec -i pacing-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $topic --property parse.key=true --property key.separator=|"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo

    $processStarted = $false
    $standardInputClosed = $false
    $stderrTask = $null
    [long]$published = 0
    $watch = New-Object System.Diagnostics.Stopwatch

    try {
        if (-not $process.Start()) {
            throw "Failed to start Kafka console producer."
        }

        $processStarted = $true

        # Drain stderr immediately so a full error buffer cannot block the producer.
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.NewLine = "`n"
        $process.StandardInput.AutoFlush = $false
        $watch.Start()

        $eventsPerBatch = [Math]::Max(1, [int][Math]::Ceiling([double]$BillingRate / 10.0))
        $progressEvery = [Math]::Max([long]$BillingRate * 10L, 1L)

        while ($published -lt $EventCount) {
            if ($process.HasExited) {
                $producerError = ($stderrTask.GetAwaiter()).GetResult()
                throw (
                    "Kafka console producer exited before all events were published. " +
                    "published=$published/$EventCount exit=$($process.ExitCode) stderr=$producerError"
                )
            }

            $batchEnd = [Math]::Min($EventCount, $published + $eventsPerBatch)

            while ($published -lt $batchEnd) {
                $index = $published
                $reservationId = "$reservationPrefix-$index"
                $eventId = "$billingPrefix-$index"
                $now = [DateTimeOffset]::UtcNow
                $occurredAt = $now.ToString("o")

                $message = [ordered]@{
                    eventId = $eventId
                    reservationId = $reservationId
                    eventType = "CHARGED"
                    actualAmount = $Amount
                    occurredAt = $occurredAt
                }

                $json = $message | ConvertTo-Json -Compress
                $producerLine = "$reservationId|$json"

                $process.StandardInput.WriteLine($producerLine)
                $published++

                if (($published % $progressEvery) -eq 0 -or $published -eq $EventCount) {
                    Write-Host "billing published: $published / $EventCount"
                }
            }

            $process.StandardInput.Flush()

            $targetMilliseconds = ([double]$published / [double]$BillingRate) * 1000.0
            $remainingMilliseconds = $targetMilliseconds - $watch.Elapsed.TotalMilliseconds
            if ($remainingMilliseconds -gt 1.0) {
                Start-Sleep -Milliseconds ([int][Math]::Floor($remainingMilliseconds))
            }
        }

        $process.StandardInput.Close()
        $standardInputClosed = $true

        if (-not $process.WaitForExit(60000)) {
            throw (
                "Kafka console producer did not exit within 60 seconds. " +
                "published=$published/$EventCount"
            )
        }

        $producerError = ($stderrTask.GetAwaiter()).GetResult()
        if ($process.ExitCode -ne 0) {
            throw (
                "Kafka console producer failed. published=$published/$EventCount " +
                "exit=$($process.ExitCode) stderr=$producerError"
            )
        }

        Write-Host "Billing publish completed: $EventCount events"
    }
    catch {
        $failureMessage = $_.Exception.Message
        $producerError = ""
        $producerExit = "not-started"

        if ($processStarted) {
            if (-not $standardInputClosed) {
                try {
                    $process.StandardInput.Close()
                    $standardInputClosed = $true
                }
                catch {
                }
            }

            if (-not $process.HasExited) {
                try {
                    [void]$process.WaitForExit(5000)
                }
                catch {
                }
            }

            if (-not $process.HasExited) {
                try {
                    $process.Kill()
                    [void]$process.WaitForExit(5000)
                }
                catch {
                }
            }

            if ($process.HasExited) {
                $producerExit = [string]$process.ExitCode
            }

            if ($null -ne $stderrTask -and $process.HasExited) {
                try {
                    $producerError = ($stderrTask.GetAwaiter()).GetResult()
                }
                catch {
                    $producerError = "Could not read producer stderr: $($_.Exception.Message)"
                }
            }
        }

        throw (
            "Billing publish failed after $published/$EventCount events. " +
            "producerExit=$producerExit cause=$failureMessage stderr=$producerError"
        )
    }
    finally {
        $watch.Stop()

        if ($processStarted) {
            if (-not $standardInputClosed) {
                try {
                    $process.StandardInput.Close()
                }
                catch {
                }
            }

            if (-not $process.HasExited) {
                try {
                    $process.Kill()
                    [void]$process.WaitForExit(5000)
                }
                catch {
                }
            }
        }

        $process.Dispose()
    }
}

$decisionContainerStarted = $false
$decisionExitCode = -1

try {
    Write-Host ""
    Write-Host "============================================"
    Write-Host " Full-System Load Test"
    Write-Host "============================================"
    Write-Host "campaign:            $campaignId"
    Write-Host "load target:         $LoadBaseUrl"
    Write-Host "decision rate:       $DecisionRate req/s"
    Write-Host "billing rate:        $BillingRate events/s"
    Write-Host "duration:            $DurationSeconds sec"
    Write-Host "billing events:      $totalBillingEvents"
    Write-Host "amount:              $Amount"
    Write-Host "expected spend:      $expectedSpend"
    Write-Host "test budget:         $testBudget"

    Write-Step "0. Prepare stack"

    if (-not $SkipStart) {
        $startParams = @{
            NoBuild = $NoBuild
            Monitoring = $Monitoring
            TimeoutSeconds = $TimeoutSeconds
            EnvironmentFile = $EnvironmentFile
        }
        & (Join-Path $PSScriptRoot "start-local.ps1") @startParams
    }
    else {
        $waitParams = @{
            TimeoutSeconds = $TimeoutSeconds
            EnvironmentFile = $EnvironmentFile
        }
        & (Join-Path $PSScriptRoot "wait-for-services.ps1") @waitParams
    }

    $networkName = Get-ApplicationDockerNetwork
    Write-Host "Docker network: $networkName"

    Write-Step "1. Create test campaign"

    $now = [DateTimeOffset]::UtcNow
    $campaignParams = @{
        CampaignId = $campaignId
        Status = "ACTIVE"
        PacingStrategy = "ASAP"
        TotalBudget = $testBudget
        DailyBudgetLimit = $testBudget
        StartAt = $now.AddMinutes(-5)
        EndAt = $now.AddDays(1)
        PassThru = $true
        EnvironmentFile = $EnvironmentFile
    }
    $campaignResponse = & (Join-Path $PSScriptRoot "upsert-campaign.ps1") @campaignParams

    Assert-Equal -Expected $campaignId -Actual ([string]$campaignResponse.Json.campaignId) -Message "Campaign id mismatch."

    Write-Step "2. Initialize budget and pacing state"

    $decisionParams = @{
        CampaignId = $campaignId
        RequestId = (New-TestIdentifier -Prefix "full-load-init")
        PassThru = $true
        EnvironmentFile = $EnvironmentFile
    }
    $initialDecision = & (Join-Path $PSScriptRoot "request-pacing-decision.ps1") @decisionParams

    Assert-Equal -Expected "PASS" -Actual ([string]$initialDecision.Json.decision) -Message "Initial decision was not PASS."

    Write-Step "3. Create reservations"

    $auctionSecret = Get-HmacSecret -ClientId "auction-server"
    $reservationK6Arguments = @(
        "run",
        "--rm",
        "--network",
        $networkName,
        "-e",
        "BASE_URL=$LoadBaseUrl",
        "-e",
        "SECRET=$auctionSecret",
        "-e",
        "CAMPAIGN_ID=$campaignId",
        "-e",
        "RESERVATION_PREFIX=$reservationPrefix",
        "-e",
        "AMOUNT=$Amount",
        "-e",
        "TOTAL_RESERVATIONS=$totalBillingEvents",
        "-e",
        "RATE=$ReservationSetupRate",
        "-e",
        "PRE_ALLOCATED_VUS=$ReservationPreAllocatedVUs",
        "-e",
        "MAX_VUS=$ReservationMaxVUs",
        "-v",
        "${loadScriptDirectory}:/scripts:ro",
        $k6Image,
        "run",
        "/scripts/full-system-reservation-setup.js"
    )
    [void](Invoke-DockerCommand -Arguments $reservationK6Arguments -StreamOutput)

    Write-Step "4. Verify reservation preconditions"

    $campaignLiteral = ConvertTo-SqlLiteral -Value $campaignId
    $sqlReservationCount = "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = $campaignLiteral"
    $sqlReservedCount = "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = $campaignLiteral AND status = 'RESERVED'"
    $sqlBudgetDateCount = "SELECT COUNT(DISTINCT budget_date) FROM budget_reservation WHERE campaign_id = $campaignLiteral"
    $sqlBudgetDate = "SELECT MIN(budget_date)::text FROM budget_reservation WHERE campaign_id = $campaignLiteral"

    $postgresReservationCount = Get-DbLong -Sql $sqlReservationCount -Name "reservation count"
    $postgresReservedCount = Get-DbLong -Sql $sqlReservedCount -Name "reserved count"
    $budgetDateCount = Get-DbLong -Sql $sqlBudgetDateCount -Name "budget date count"
    $budgetDate = Invoke-PostgresScalar -Sql $sqlBudgetDate

    Write-Host "Reservations persisted: $postgresReservationCount / $totalBillingEvents"
    Write-Host "Reservations RESERVED:  $postgresReservedCount / $totalBillingEvents"

    Assert-Equal -Expected $totalBillingEvents -Actual $postgresReservationCount -Message "Reservation fixture count mismatch."
    Assert-Equal -Expected $totalBillingEvents -Actual $postgresReservedCount -Message "Reservation fixture is not fully RESERVED."
    Assert-Equal -Expected 1L -Actual $budgetDateCount -Message "Reservations span multiple budget dates."

    if ([string]::IsNullOrWhiteSpace($budgetDate)) {
        throw "Could not read budget_date."
    }

    $encodedCampaignId = ConvertTo-RedisKeyPart -Value $campaignId
    $totalBudgetKey = "pacing:budget:total:{$encodedCampaignId}"
    $dailyBudgetKey = "pacing:budget:daily:{$encodedCampaignId}:$budgetDate"

    $preTotalSpent = Get-RedisBudgetLong -Key $totalBudgetKey -Field "totalSpentAmount"
    $preTotalReserved = Get-RedisBudgetLong -Key $totalBudgetKey -Field "totalReservedAmount"
    $preDailySpent = Get-RedisBudgetLong -Key $dailyBudgetKey -Field "dailySpentAmount"
    $preDailyReserved = Get-RedisBudgetLong -Key $dailyBudgetKey -Field "dailyReservedAmount"

    Assert-Equal -Expected 0L -Actual $preTotalSpent -Message "Pre-test total spent must be 0."
    Assert-Equal -Expected $expectedSpend -Actual $preTotalReserved -Message "Pre-test total reserved mismatch."
    Assert-Equal -Expected 0L -Actual $preDailySpent -Message "Pre-test daily spent must be 0."
    Assert-Equal -Expected $expectedSpend -Actual $preDailyReserved -Message "Pre-test daily reserved mismatch."

    Write-Step "5. Run decision load and billing load together"

    Start-DecisionLoadContainer -DockerNetwork $networkName
    $decisionContainerStarted = $true
    Start-Sleep -Milliseconds 500

    Publish-BillingLoad -EventCount $totalBillingEvents

    Write-Step "6. Wait for decision load"
    $decisionExitCode = Wait-DecisionLoadContainer

    Write-Step "7. Wait for worker completion"

    $billingPatternLiteral = ConvertTo-SqlLiteral -Value ($billingPrefix + "-%")
    $sqlCompletedBilling = "SELECT COUNT(*) FROM billing_event WHERE event_id LIKE $billingPatternLiteral AND processing_status = 'COMPLETED'"

    Wait-Until -TimeoutSeconds $TimeoutSeconds -IntervalMilliseconds 1000 -FailureMessage "Worker did not complete all billing events." -Condition {
        $completed = Get-DbLong -Sql $sqlCompletedBilling -Name "completed billing count"
        Write-Host "worker completed: $completed / $totalBillingEvents"
        return $completed -eq $totalBillingEvents
    }

    Write-Step "8. Wait for Kafka lag to reach zero"

    Wait-Until -TimeoutSeconds $TimeoutSeconds -IntervalMilliseconds 1000 -FailureMessage "Kafka consumer lag did not reach zero." -Condition {
        $lag = Get-KafkaConsumerLag
        Write-Host "Kafka lag: $lag"
        return $lag -eq 0
    }

    $finalKafkaLag = Get-KafkaConsumerLag

    Write-Step "9. Verify PostgreSQL"

    $sqlFinalReservationCount = "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = $campaignLiteral"
    $sqlConfirmedCount = "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = $campaignLiteral AND status = 'CONFIRMED'"
    $sqlRemainingReservedCount = "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = $campaignLiteral AND status = 'RESERVED'"
    $sqlAppliedAmount = "SELECT COALESCE(SUM(applied_amount), 0) FROM budget_reservation WHERE campaign_id = $campaignLiteral"
    $sqlBillingEventCount = "SELECT COUNT(*) FROM billing_event WHERE event_id LIKE $billingPatternLiteral"
    $sqlDeadLetterCount = "SELECT COUNT(*) FROM billing_event WHERE event_id LIKE $billingPatternLiteral AND processing_status = 'DEAD_LETTER'"
    $sqlConfirmedResultCount = "SELECT COUNT(*) FROM billing_event WHERE event_id LIKE $billingPatternLiteral AND processing_status = 'COMPLETED' AND result_status = 'CONFIRMED'"
    $sqlTotalOverage = "SELECT COALESCE(SUM(total_overage_amount), 0) FROM billing_event WHERE event_id LIKE $billingPatternLiteral"
    $sqlDailyOverage = "SELECT COALESCE(SUM(daily_overage_amount), 0) FROM billing_event WHERE event_id LIKE $billingPatternLiteral"

    $finalReservationCount = Get-DbLong -Sql $sqlFinalReservationCount -Name "final reservation count"
    $confirmedReservationCount = Get-DbLong -Sql $sqlConfirmedCount -Name "confirmed reservation count"
    $remainingReservedCount = Get-DbLong -Sql $sqlRemainingReservedCount -Name "remaining reserved count"
    $postgresAppliedAmount = Get-DbLong -Sql $sqlAppliedAmount -Name "applied amount"
    $billingEventCount = Get-DbLong -Sql $sqlBillingEventCount -Name "billing event count"
    $completedBillingCount = Get-DbLong -Sql $sqlCompletedBilling -Name "completed billing count"
    $deadLetterCount = Get-DbLong -Sql $sqlDeadLetterCount -Name "dead letter count"
    $confirmedResultCount = Get-DbLong -Sql $sqlConfirmedResultCount -Name "confirmed result count"
    $totalOverage = Get-DbLong -Sql $sqlTotalOverage -Name "total overage"
    $dailyOverage = Get-DbLong -Sql $sqlDailyOverage -Name "daily overage"

    Assert-Equal -Expected $totalBillingEvents -Actual $finalReservationCount -Message "Final reservation count mismatch."
    Assert-Equal -Expected $totalBillingEvents -Actual $confirmedReservationCount -Message "CONFIRMED reservation count mismatch."
    Assert-Equal -Expected 0L -Actual $remainingReservedCount -Message "RESERVED rows remain after billing."
    Assert-Equal -Expected $expectedSpend -Actual $postgresAppliedAmount -Message "PostgreSQL applied amount mismatch."
    Assert-Equal -Expected $totalBillingEvents -Actual $billingEventCount -Message "Billing event count mismatch."
    Assert-Equal -Expected $totalBillingEvents -Actual $completedBillingCount -Message "COMPLETED billing event count mismatch."
    Assert-Equal -Expected 0L -Actual $deadLetterCount -Message "DEAD_LETTER events exist."
    Assert-Equal -Expected $totalBillingEvents -Actual $confirmedResultCount -Message "CONFIRMED billing result count mismatch."
    Assert-Equal -Expected 0L -Actual $totalOverage -Message "Total budget overage detected."
    Assert-Equal -Expected 0L -Actual $dailyOverage -Message "Daily budget overage detected."

    Write-Step "10. Verify Redis"

    $redisTotalSpent = Get-RedisBudgetLong -Key $totalBudgetKey -Field "totalSpentAmount"
    $redisTotalReserved = Get-RedisBudgetLong -Key $totalBudgetKey -Field "totalReservedAmount"
    $redisDailySpent = Get-RedisBudgetLong -Key $dailyBudgetKey -Field "dailySpentAmount"
    $redisDailyReserved = Get-RedisBudgetLong -Key $dailyBudgetKey -Field "dailyReservedAmount"

    Assert-Equal -Expected $expectedSpend -Actual $redisTotalSpent -Message "Redis total spent mismatch."
    Assert-Equal -Expected 0L -Actual $redisTotalReserved -Message "Redis total reserved must be 0."
    Assert-Equal -Expected $expectedSpend -Actual $redisDailySpent -Message "Redis daily spent mismatch."
    Assert-Equal -Expected 0L -Actual $redisDailyReserved -Message "Redis daily reserved must be 0."
    Assert-Equal -Expected $postgresAppliedAmount -Actual $redisTotalSpent -Message "Redis and PostgreSQL total spend differ."
    Assert-Equal -Expected $postgresAppliedAmount -Actual $redisDailySpent -Message "Redis and PostgreSQL daily spend differ."

    Write-Step "11. Final result"
    Write-Host "campaignId:          $campaignId"
    Write-Host "budgetDate:          $budgetDate"
    Write-Host "decisionRate:        $DecisionRate RPS"
    Write-Host "billingRate:         $BillingRate events/s"
    Write-Host "billingEvents:       $billingEventCount"
    Write-Host "confirmed:           $confirmedReservationCount"
    Write-Host "deadLetter:          $deadLetterCount"
    Write-Host "kafkaLag:            $finalKafkaLag"
    Write-Host "postgresApplied:     $postgresAppliedAmount"
    Write-Host "redisTotalSpent:     $redisTotalSpent"
    Write-Host "redisTotalReserved:  $redisTotalReserved"
    Write-Host "expectedSpend:       $expectedSpend"

    if ($decisionExitCode -ne 0) {
        throw "Decision k6 failed its thresholds. exit=$decisionExitCode"
    }

    Write-Host ""
    Write-Host "Full-System Load Test SUCCESS" -ForegroundColor Green
}
catch {
    Write-Host ""
    Write-Host "Full-System Load Test FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red

    try {
        Write-Host ""
        Write-Host "----- application logs -----" -ForegroundColor Yellow
        Push-Location (Get-ProjectRoot)
        try {
            [void](Invoke-DockerCommand -Arguments @(
                "compose",
                "--profile",
                "application",
                "logs",
                "--tail",
                "200",
                "pacing-api",
                "pacing-worker"
            ) -StreamOutput)
        }
        finally {
            Pop-Location
        }
    }
    catch {
        Write-Host "Could not print application logs."
    }

    try {
        Write-Host ""
        Write-Host "----- Kafka consumer group -----" -ForegroundColor Yellow
        [void](Invoke-DockerCommand -Arguments @(
            "exec",
            "pacing-kafka",
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server",
            "localhost:9092",
            "--describe",
            "--group",
            $consumerGroup
        ) -StreamOutput)
    }
    catch {
        Write-Host "Could not print Kafka consumer group."
    }

    throw
}
finally {
    if ($decisionContainerStarted) {
        try {
            [void](Invoke-DockerCommand -Arguments @("rm", "-f", $decisionContainerName))
        }
        catch {
        }
    }
}
