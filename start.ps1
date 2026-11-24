# P1-01: Start the local deployment. Online models stay disabled.
[CmdletBinding()]
param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'

function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: $($args[0]). See the Docker output above."
    }
}

function New-Password {
    $bytes = New-Object byte[] 24
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

$project = 'knowledge-assistant-onboarding'
$mutex = New-Object Threading.Mutex($false, 'Local\knowledge-assistant-onboarding')
$locked = $false
$minioImageSet = $false
Push-Location -LiteralPath $PSScriptRoot
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw 'Another start.ps1 is running.' }

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Install and start Docker Desktop first.' }
    $platform = Invoke-Docker info --format '{{.OSType}}/{{.Architecture}}'
    if ($platform -notin @('linux/x86_64', 'linux/amd64')) { throw 'Docker Desktop must run Linux amd64 containers.' }
    Invoke-Docker compose version

    $envFile = Join-Path $PSScriptRoot '.env.onboarding'
    $composeArgs = @('compose', '--project-name', $project, '--env-file', $envFile,
        '-f', 'compose.yaml', '-f', 'compose.onboarding.yaml')

    # Compose gives shell variables precedence over the dedicated env file.
    foreach ($name in @('KA_INFRA_PG_PASSWORD', 'KA_INFRA_MINIO_USER', 'KA_INFRA_MINIO_PASSWORD',
        'KA_MINIO_BUCKET', 'KA_WEB_PORT', 'KA_ONBOARDING_MINIO_IMAGE')) {
        if (Test-Path "Env:$name") { throw "Unset the process variable $name before running start.ps1." }
    }

    if (-not (Test-Path -LiteralPath $envFile)) {
        $existingVolumes = Invoke-Docker volume ls -q --filter "label=com.docker.compose.project=$project"
        $existingContainers = Invoke-Docker ps -aq --filter "label=com.docker.compose.project=$project"
        if ($existingVolumes -or $existingContainers) {
            throw 'Deployment data already exists. Restore its original .env.onboarding before starting.'
        }
        $settings = @(
            "KA_INFRA_PG_PASSWORD=$(New-Password)"
            "KA_INFRA_MINIO_USER=ka-$(New-Password)"
            "KA_INFRA_MINIO_PASSWORD=$(New-Password)"
            'KA_MINIO_BUCKET=knowledge-assistant'
            'KA_WEB_PORT=8080'
        )
        $temporaryFile = "$envFile.$([Guid]::NewGuid().ToString('N'))"
        try {
            [IO.File]::WriteAllLines($temporaryFile, $settings)
            [IO.File]::Move($temporaryFile, $envFile)
        } finally {
            if (Test-Path -LiteralPath $temporaryFile) { Remove-Item -LiteralPath $temporaryFile }
        }
    }

    # Reuse the known local image or build from checksum-pinned upstream source.
    $digest = 'sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e'
    $legacy = 'knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local'
    $sourceImage = 'knowledge-assistant-onboarding-minio:2025-09-07-source'
    $localInfo = Invoke-Docker image ls --no-trunc --format '{{.ID}}' $legacy
    $env:KA_ONBOARDING_MINIO_IMAGE = $sourceImage
    $minioImageSet = $true
    if ($localInfo -eq $digest) { $env:KA_ONBOARDING_MINIO_IMAGE = $legacy }

    Invoke-Docker @composeArgs config --quiet
    $portRecords = @(Invoke-Docker @composeArgs config --format json |
        ConvertFrom-Json | ForEach-Object { $_.services.app.ports })
    $port = [int]$portRecords[0].published
    if ($port -lt 1 -or $port -gt 65535) { throw 'Set KA_WEB_PORT in .env.onboarding to a port between 1 and 65535.' }
    $published = Invoke-Docker @composeArgs ps --status running -q app
    $appPort = $null
    if ($published) { $appPort = Invoke-Docker port $published 8080/tcp }
    if ($appPort -ne "127.0.0.1:${port}") {
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $port)
        try {
            $listener.Server.ExclusiveAddressUse = $true
            $listener.Start()
        } catch {
            throw "Web port $port is unavailable. Set KA_WEB_PORT in .env.onboarding to a free port."
        } finally { $listener.Stop() }
    }

    if ($env:KA_ONBOARDING_MINIO_IMAGE -eq $sourceImage) {
        Invoke-Docker build --file infra/minio/Dockerfile.source --tag $sourceImage .
    }

    try {
        Invoke-Docker @composeArgs up --detach --build --wait --wait-timeout 360
    } catch {
        & docker @composeArgs ps --all
        & docker @composeArgs logs --tail 20 minio-init
        throw
    }
    $url = "http://127.0.0.1:$port/api/index.html"
    Write-Host "Knowledge Assistant is ready at $url (online models disabled)."
    if (-not $NoBrowser) { Start-Process $url }
} finally {
    if ($locked) {
        if ($minioImageSet) { Remove-Item Env:KA_ONBOARDING_MINIO_IMAGE -ErrorAction SilentlyContinue }
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
    Pop-Location
}
