Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Net.Http

$script:ProjectRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\..")
)

function Get-ProjectRoot {
    return $script:ProjectRoot
}

function Write-Step {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Import-LocalEnvironment {
    param(
        [string]$EnvironmentFile
    )

    if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
        $localFile = Join-Path $script:ProjectRoot ".env"
        $exampleFile = Join-Path $script:ProjectRoot ".env.example"
        $EnvironmentFile = if (Test-Path -LiteralPath $localFile) {
            $localFile
        }
        else {
            $exampleFile
        }
    }

    if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
        throw "환경변수 파일을 찾을 수 없습니다: $EnvironmentFile"
    }

    foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or
            $trimmed.StartsWith("#")) {
            continue
        }

        $separatorIndex = $trimmed.IndexOf("=")
        if ($separatorIndex -le 0) {
            continue
        }

        $name = $trimmed.Substring(0, $separatorIndex).Trim()
        $value = $trimmed.Substring($separatorIndex + 1).Trim()

        if ([string]::IsNullOrWhiteSpace(
                [Environment]::GetEnvironmentVariable(
                    $name,
                    "Process"
                )
            )) {
            [Environment]::SetEnvironmentVariable(
                $name,
                $value,
                "Process"
            )
        }
    }

    return $EnvironmentFile
}

function Get-EnvironmentValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [string]$DefaultValue,

        [switch]$Required
    )

    $value = [Environment]::GetEnvironmentVariable(
        $Name,
        "Process"
    )

    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = $DefaultValue
    }

    if ($Required -and [string]::IsNullOrWhiteSpace($value)) {
        throw "필수 환경변수가 설정되지 않았습니다: $Name"
    }

    return $value
}

function Get-ApiBaseUrl {
    $configured = Get-EnvironmentValue `
        -Name "PACING_API_BASE_URL"

    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        return $configured.TrimEnd("/")
    }

    $port = Get-EnvironmentValue `
        -Name "PACING_API_PORT" `
        -DefaultValue "8080"

    return "http://localhost:$port"
}

function Get-WorkerBaseUrl {
    $configured = Get-EnvironmentValue `
        -Name "PACING_WORKER_BASE_URL"

    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        return $configured.TrimEnd("/")
    }

    $port = Get-EnvironmentValue `
        -Name "PACING_WORKER_PORT" `
        -DefaultValue "8081"

    return "http://localhost:$port"
}

function Get-ApiGlowrootUrl {
    $port = Get-EnvironmentValue `
        -Name "PACING_GLOWROOT_API_PORT" `
        -DefaultValue "4000"

    return "http://localhost:$port"
}

function Get-WorkerGlowrootUrl {
    $port = Get-EnvironmentValue `
        -Name "PACING_GLOWROOT_WORKER_PORT" `
        -DefaultValue "4001"

    return "http://localhost:$port"
}

function Get-HmacSecret {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet(
            "ad-server",
            "auction-server",
            "operation-server"
        )]
        [string]$ClientId
    )

    $environmentName = switch ($ClientId) {
        "ad-server" {
            "PACING_HMAC_AD_SERVER_CURRENT_SECRET"
        }
        "auction-server" {
            "PACING_HMAC_AUCTION_SERVER_CURRENT_SECRET"
        }
        "operation-server" {
            "PACING_HMAC_OPERATION_SERVER_CURRENT_SECRET"
        }
    }

    return Get-EnvironmentValue `
        -Name $environmentName `
        -Required
}

function ConvertTo-Hex {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes
    )

    $hex = [BitConverter]::ToString($Bytes)
    return $hex.Replace("-", "").ToLowerInvariant()
}

function Get-Sha256Hex {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes
    )

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ConvertTo-Hex -Bytes $sha256.ComputeHash($Bytes)
    }
    finally {
        $sha256.Dispose()
    }
}

function New-HmacHeaders {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$ClientId,

        [Parameter(Mandatory = $true)]
        [string]$Secret,

        [Parameter(Mandatory = $true)]
        [string]$BodyText,

        [string]$Timestamp,

        [string]$Nonce
    )

    if ([string]::IsNullOrWhiteSpace($Timestamp)) {
        $now = [DateTimeOffset]::UtcNow
        $Timestamp = $now.ToUnixTimeSeconds().ToString()
    }

    if ([string]::IsNullOrWhiteSpace($Nonce)) {
        $Nonce = [Guid]::NewGuid().ToString("N")
    }

    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($BodyText)
    $bodyHash = Get-Sha256Hex -Bytes $bodyBytes
    $canonicalRequest = @(
        $Method.ToUpperInvariant()
        $Path
        $ClientId
        $Timestamp
        $Nonce
        $bodyHash
    ) -join "`n"

    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($Secret)
    )

    try {
        $signature = ConvertTo-Hex -Bytes (
            $hmac.ComputeHash(
                [Text.Encoding]::UTF8.GetBytes(
                    $canonicalRequest
                )
            )
        )
    }
    finally {
        $hmac.Dispose()
    }

    return @{
        "X-Client-Id" = $ClientId
        "X-Timestamp" = $Timestamp
        "X-Nonce" = $Nonce
        "X-Signature" = $signature
    }
}

function Invoke-HttpRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Uri,

        [Parameter(Mandatory = $true)]
        [hashtable]$Headers,

        [string]$BodyText,

        [switch]$AttachJsonBody
    )

    $httpMethod = [System.Net.Http.HttpMethod]::new(
        $Method.ToUpperInvariant()
    )
    $request = [System.Net.Http.HttpRequestMessage]::new(
        $httpMethod,
        $Uri
    )
    $client = [System.Net.Http.HttpClient]::new()

    try {
        foreach ($entry in $Headers.GetEnumerator()) {
            [void]$request.Headers.TryAddWithoutValidation(
                [string]$entry.Key,
                [string]$entry.Value
            )
        }

        if ($AttachJsonBody) {
            $request.Content = [System.Net.Http.StringContent]::new(
                $BodyText,
                [Text.Encoding]::UTF8,
                "application/json"
            )
        }

        $responseTask = $client.SendAsync($request)
        $response = $responseTask.GetAwaiter().GetResult()

        try {
            $contentTask = $response.Content.ReadAsStringAsync()
            $responseBody = $contentTask.GetAwaiter().GetResult()
            $json = $null

            if (-not [string]::IsNullOrWhiteSpace($responseBody)) {
                try {
                    $json = $responseBody | ConvertFrom-Json
                }
                catch {
                    $json = $null
                }
            }

            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Body = $responseBody
                Json = $json
                Headers = $response.Headers
            }
        }
        finally {
            $response.Dispose()
        }
    }
    finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Invoke-SignedJsonRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [ValidateSet(
            "ad-server",
            "auction-server",
            "operation-server"
        )]
        [string]$ClientId,

        [object]$Body,

        [string]$Secret,

        [string]$Timestamp,

        [string]$Nonce,

        [string]$BaseUrl
    )

    if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
        $BaseUrl = Get-ApiBaseUrl
    }

    if ([string]::IsNullOrWhiteSpace($Secret)) {
        $Secret = Get-HmacSecret -ClientId $ClientId
    }

    $attachBody = $null -ne $Body
    $bodyText = if ($attachBody) {
        $Body | ConvertTo-Json -Depth 20 -Compress
    }
    else {
        ""
    }

    $headers = New-HmacHeaders `
        -Method $Method `
        -Path $Path `
        -ClientId $ClientId `
        -Secret $Secret `
        -BodyText $bodyText `
        -Timestamp $Timestamp `
        -Nonce $Nonce

    return Invoke-HttpRequest `
        -Method $Method `
        -Uri ($BaseUrl.TrimEnd("/") + $Path) `
        -Headers $headers `
        -BodyText $bodyText `
        -AttachJsonBody:$attachBody
}

function Invoke-UnsignedRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Uri
    )

    return Invoke-HttpRequest `
        -Method "GET" `
        -Uri $Uri `
        -Headers @{}
}

function Assert-HttpStatus {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Response,

        [Parameter(Mandatory = $true)]
        [int[]]$ExpectedStatus,

        [Parameter(Mandatory = $true)]
        [string]$Operation
    )

    if ($ExpectedStatus -notcontains $Response.StatusCode) {
        throw (
            "$Operation 실패: 예상 HTTP 상태=" +
            ($ExpectedStatus -join ",") +
            ", 실제 HTTP 상태=$($Response.StatusCode), " +
            "응답=$($Response.Body)"
        )
    }
}

function Assert-Equal {
    param(
        [object]$Expected,
        [object]$Actual,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if ($Expected -ne $Actual) {
        throw "$Message (예상=$Expected, 실제=$Actual)"
    }
}

function Wait-Until {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Condition,

        [int]$TimeoutSeconds = 60,

        [int]$IntervalMilliseconds = 1000,

        [Parameter(Mandatory = $true)]
        [string]$FailureMessage
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(
        $TimeoutSeconds
    )
    $lastError = $null

    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            if (& $Condition) {
                return
            }
            $lastError = $null
        }
        catch {
            $lastError = $_
        }

        Start-Sleep -Milliseconds $IntervalMilliseconds
    }

    if ($null -ne $lastError) {
        throw "$FailureMessage 마지막 오류: $($lastError.Exception.Message)"
    }

    throw $FailureMessage
}

function Wait-ForHttpHealth {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Url,

        [int]$TimeoutSeconds = 180
    )

    Wait-Until `
        -TimeoutSeconds $TimeoutSeconds `
        -IntervalMilliseconds 2000 `
        -FailureMessage "$Name health check 시간 초과: $Url" `
        -Condition {
            $response = Invoke-UnsignedRequest -Uri $Url
            return $response.StatusCode -eq 200
        }
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @(),

        [switch]$StreamOutput
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $output = $null
    $exitCode = -1

    try {
        $ErrorActionPreference = "Continue"

        if ($StreamOutput) {
            & $FilePath @Arguments 2>&1 |
                ForEach-Object {
                    Write-Host ([string]$_)
                }
        }
        else {
            $output = & $FilePath @Arguments 2>&1
        }

        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        $renderedOutput = if ($null -eq $output) {
            ""
        }
        else {
            ($output | ForEach-Object {
                [string]$_
            }) -join [Environment]::NewLine
        }

        throw (
            "$FilePath 명령 실패(exit=$exitCode)" +
            $(if ([string]::IsNullOrWhiteSpace($renderedOutput)) {
                ""
            }
            else {
                ": $renderedOutput"
            })
        )
    }

    return $output
}

function Invoke-DockerCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$StreamOutput
    )

    return Invoke-NativeCommand `
        -FilePath "docker" `
        -Arguments $Arguments `
        -StreamOutput:$StreamOutput
}

function Invoke-PostgresScalar {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $database = Get-EnvironmentValue `
        -Name "PACING_POSTGRES_DATABASE" `
        -DefaultValue "pacing"
    $username = Get-EnvironmentValue `
        -Name "PACING_POSTGRES_USERNAME" `
        -DefaultValue "pacing"
    $password = Get-EnvironmentValue `
        -Name "PACING_POSTGRES_PASSWORD" `
        -DefaultValue "pacing"

    $output = Invoke-DockerCommand -Arguments @(
        "exec",
        "-e",
        "PGPASSWORD=$password",
        "pacing-postgres",
        "psql",
        "-U",
        $username,
        "-d",
        $database,
        "-tA",
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        $Sql
    )

    return (($output | ForEach-Object {
        [string]$_
    }) -join "`n").Trim()
}

function Invoke-RedisScalar {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$RedisArguments
    )

    $password = Get-EnvironmentValue `
        -Name "PACING_REDIS_PASSWORD" `
        -DefaultValue "pacing"

    $arguments = @(
        "exec",
        "pacing-redis",
        "redis-cli",
        "--raw",
        "--no-auth-warning",
        "-a",
        $password
    ) + $RedisArguments

    $output = Invoke-DockerCommand -Arguments $arguments

    return (($output | ForEach-Object {
        [string]$_
    }) -join "`n").Trim()
}

function ConvertTo-SqlLiteral {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    return "'" + $Value.Replace("'", "''") + "'"
}

function ConvertTo-RedisKeyPart {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $encoded = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($Value)
    )

    $withoutPadding = $encoded.TrimEnd("=")
    return $withoutPadding.Replace("+", "-").Replace("/", "_")
}

function New-TestIdentifier {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prefix
    )

    $timestamp = [DateTimeOffset]::UtcNow.ToString(
        "yyyyMMddHHmmss"
    )
    $suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)

    return "$Prefix-$timestamp-$suffix"
}
