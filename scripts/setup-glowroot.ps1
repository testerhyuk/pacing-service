[CmdletBinding()]
param(
    [string]$GlowrootHome
)

. (Join-Path $PSScriptRoot "lib\common.ps1")

$projectRoot = Get-ProjectRoot
$runtimeRoot = Join-Path $projectRoot ".glowroot"
$applications = @(
    "pacing-api",
    "pacing-worker"
)
$minimumVersion = [Version]"0.14.5"

function Test-GlowrootRuntimeReady {
    foreach ($application in $applications) {
        $agentJar = Join-Path (
            Join-Path $runtimeRoot $application
        ) "glowroot.jar"

        if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
            return $false
        }
    }

    return $true
}

function Get-GlowrootVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AgentJar
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $archive = [System.IO.Compression.ZipFile]::OpenRead($AgentJar)
    try {
        $manifest = $archive.GetEntry("META-INF/MANIFEST.MF")
        if ($null -eq $manifest) {
            throw "Glowroot Agent manifest를 찾을 수 없습니다."
        }

        $reader = New-Object System.IO.StreamReader(
            $manifest.Open()
        )
        try {
            $manifestText = $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }

    $versionMatch = [regex]::Match(
        $manifestText,
        "(?m)^Implementation-Version:\s*(\d+\.\d+\.\d+)"
    )

    if (-not $versionMatch.Success) {
        throw "Glowroot Agent 버전을 확인할 수 없습니다."
    }

    return [Version]$versionMatch.Groups[1].Value
}

if ([string]::IsNullOrWhiteSpace($GlowrootHome)) {
    $GlowrootHome = Get-EnvironmentValue `
        -Name "PACING_GLOWROOT_HOME"
}

if ([string]::IsNullOrWhiteSpace($GlowrootHome)) {
    if (Test-GlowrootRuntimeReady) {
        foreach ($application in $applications) {
            $runtimeJar = Join-Path (
                Join-Path $runtimeRoot $application
            ) "glowroot.jar"
            $runtimeVersion = Get-GlowrootVersion `
                -AgentJar $runtimeJar

            if ($runtimeVersion -lt $minimumVersion) {
                throw (
                    "$application Glowroot $runtimeVersion 버전은 " +
                    "Java 21 계측에 사용할 수 없습니다. " +
                    "Glowroot $minimumVersion 이상을 다시 지정하세요."
                )
            }
        }

        Write-Host (
            "호환되는 Glowroot 실행 파일이 이미 준비되어 있습니다."
        )
        return
    }

    throw (
        "Glowroot 설치 경로가 필요합니다. " +
        "-GlowrootHome 또는 PACING_GLOWROOT_HOME을 지정하세요."
    )
}

$sourceRoot = [System.IO.Path]::GetFullPath($GlowrootHome)
$sourceJar = Join-Path $sourceRoot "glowroot.jar"
$sourceLib = Join-Path $sourceRoot "lib"

if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
    throw "Glowroot Agent를 찾을 수 없습니다: $sourceJar"
}

if (-not (Test-Path -LiteralPath $sourceLib -PathType Container)) {
    throw "Glowroot lib 폴더를 찾을 수 없습니다: $sourceLib"
}

$sourceVersion = Get-GlowrootVersion -AgentJar $sourceJar

if ($sourceVersion -lt $minimumVersion) {
    throw (
        "Glowroot $sourceVersion 버전은 Java 21 계측에 사용할 수 없습니다. " +
        "Glowroot $minimumVersion 이상을 지정하세요."
    )
}

$adminConfiguration = @'
{
  "users": [
    {
      "username": "anonymous",
      "roles": [
        "Administrator"
      ]
    }
  ],
  "roles": [
    {
      "name": "Administrator",
      "permissions": [
        "agent:transaction",
        "agent:error",
        "agent:jvm",
        "agent:incident",
        "agent:config",
        "admin"
      ]
    }
  ],
  "web": {
    "port": 4000,
    "bindAddress": "0.0.0.0",
    "contextPath": "/",
    "sessionTimeoutMinutes": 30,
    "sessionCookieName": "GLOWROOT_SESSION_ID"
  }
}
'@

$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

Write-Step "Glowroot $sourceVersion API·Worker 실행 디렉터리 준비"

foreach ($application in $applications) {
    $targetRoot = Join-Path $runtimeRoot $application
    $targetLib = Join-Path $targetRoot "lib"
    $targetPlugins = Join-Path $targetRoot "plugins"

    [void](New-Item `
        -ItemType Directory `
        -Path $targetLib `
        -Force)

    Copy-Item `
        -LiteralPath $sourceJar `
        -Destination (Join-Path $targetRoot "glowroot.jar") `
        -Force

    Get-ChildItem -LiteralPath $sourceLib -File |
        Copy-Item -Destination $targetLib -Force

    $sourcePlugins = Join-Path $sourceRoot "plugins"
    if (Test-Path -LiteralPath $sourcePlugins -PathType Container) {
        [void](New-Item `
            -ItemType Directory `
            -Path $targetPlugins `
            -Force)

        Copy-Item `
            -Path (Join-Path $sourcePlugins "*") `
            -Destination $targetPlugins `
            -Recurse `
            -Force
    }

    [System.IO.File]::WriteAllText(
        (Join-Path $targetRoot "admin.json"),
        $adminConfiguration,
        $utf8WithoutBom
    )

    [System.IO.File]::WriteAllText(
        (Join-Path $targetRoot "glowroot.properties"),
        "agent.id=$application`n",
        $utf8WithoutBom
    )

    Write-Host "${application}: $targetRoot"
}

Write-Host ""
Write-Host "Glowroot 실행 파일 준비 완료" -ForegroundColor Green
