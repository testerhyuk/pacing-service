[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReservationId,

    [ValidateSet("CHARGED", "CANCELLED", "ADJUSTED")]
    [string]$EventType = "CHARGED",

    [long]$ActualAmount = 1000,

    [string]$EventId,

    [DateTimeOffset]$OccurredAt,

    [string]$Topic = "billing.events.v1",

    [switch]$PassThru,

    [string]$EnvironmentFile
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

[void](Import-LocalEnvironment -EnvironmentFile $EnvironmentFile)

if ([string]::IsNullOrWhiteSpace($EventId)) {
    $EventId = New-TestIdentifier -Prefix "billing"
}

if ($ActualAmount -le 0) {
    throw "실제 과금 금액은 0보다 커야 합니다."
}

if ($OccurredAt -eq [DateTimeOffset]::MinValue) {
    $OccurredAt = [DateTimeOffset]::UtcNow
}

$message = @{
    eventId = $EventId
    reservationId = $ReservationId
    eventType = $EventType
    actualAmount = $ActualAmount
    occurredAt = $OccurredAt.ToUniversalTime().ToString("o")
}

$json = $message | ConvertTo-Json -Compress
$producerLine = "$ReservationId|$json"

Write-Step "Kafka 과금 이벤트 발행: $EventId ($EventType)"

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $producerLine | & docker exec -i `
        pacing-kafka `
        /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server localhost:9092 `
        --topic $Topic `
        --property parse.key=true `
        --property "key.separator=|"
    $producerExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

if ($producerExitCode -ne 0) {
    throw "Kafka 과금 이벤트 발행에 실패했습니다."
}

Write-Host $json

if ($PassThru) {
    return [pscustomobject]$message
}
