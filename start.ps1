# Start the local deployment with explicitly authorized online models.
[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [switch]$ConfigureModel,
    [switch]$RevokeModelAuthorization
)
$ErrorActionPreference = 'Stop'
if ($ConfigureModel -and $RevokeModelAuthorization) {
    throw 'Choose either -ConfigureModel or -RevokeModelAuthorization.'
}

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

function Get-ModelSettings([string]$Revision, [string]$Key) {
    $enabled = if ($Key) { 'true' } else { 'false' }
    @(
        "# revision=$Revision"
        "assistant.model.api-key=$Key"
        "assistant.model.enabled=$enabled"
        "assistant.model.online-authorized=$enabled"
        "assistant.worker.enabled=$enabled"
        'assistant.model.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1'
        'assistant.model.chat-model=qwen-plus'
        'assistant.model.embedding-model=text-embedding-v4'
        'assistant.model.dimensions=1024'
    )
}

function Read-ModelState([string]$Path) {
    if (-not [IO.File]::Exists($Path)) { return }
    $lines = [IO.File]::ReadAllLines($Path)
    if ($lines.Length -lt 2 -or $lines[0] -cnotmatch '^# revision=([0-9a-f]{32})$') { return }
    $revision = $Matches[1]
    if ($lines[1] -cnotmatch '^assistant\.model\.api-key=(sk-[A-Za-z0-9_-]+)?$') { return }
    $key = $Matches[1]
    # Only reuse a complete generated record with a consistent authorization state.
    $expected = Get-ModelSettings $revision $key
    if (($lines -join "`n") -ceq ($expected -join "`n")) {
        [PSCustomObject]@{ Revision = $revision; Enabled = [bool]$key }
    }
}

function Read-ModelKey {
    $secureKey = Read-Host 'Enter your Alibaba Cloud Model Studio API Key (empty cancels)' -AsSecureString
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
        try {
            $key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
            if (-not $key) { return }
            if ($key -cnotmatch '^sk-[A-Za-z0-9_-]+\z') {
                throw 'Invalid API Key format. Run start.ps1 again to retry.'
            }
            return $key
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    } finally {
        $secureKey.Dispose()
    }
}

function Write-ModelSettings([string]$Path, [string]$Revision, [string]$Key) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    $temp = "$Path.$Revision.tmp"
    try {
        [IO.File]::WriteAllLines($temp, [string[]](Get-ModelSettings $Revision $Key))
        if ([IO.File]::Exists($Path)) {
            [IO.File]::Replace($temp, $Path, [NullString]::Value)
        } else {
            [IO.File]::Move($temp, $Path)
        }
    } finally {
        if ([IO.File]::Exists($temp)) { [IO.File]::Delete($temp) }
    }
}

$project = 'knowledge-assistant-onboarding'
$mutex = New-Object Threading.Mutex($false, 'Local\knowledge-assistant-onboarding')
$locked = $false
$minioImageSet = $false
$modelRevisionSet = $false
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
        'KA_MINIO_BUCKET', 'KA_WEB_PORT', 'KA_ONBOARDING_MINIO_IMAGE', 'KA_ONBOARDING_MODEL_REVISION')) {
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

    $modelPath = Join-Path $PSScriptRoot '.onboarding-model/online.properties'
    $modelState = Read-ModelState $modelPath
    if (-not $modelState -or $ConfigureModel -or $RevokeModelAuthorization) {
        $key = $null
        if (-not $RevokeModelAuthorization) {
            Write-Host 'Online calls may incur charges and send questions and document content to Alibaba Cloud Model Studio.'
            $consent = Read-Host 'Type YES to authorize online calls and charges, NO to decline, or press Enter to cancel'
            if ($consent -eq 'YES') {
                $key = Read-ModelKey
                if (-not $key) { Write-Host 'Model setup cancelled.'; return }
            } elseif ($consent -ne 'NO' -or $ConfigureModel) {
                Write-Host 'Model setup cancelled. Use -RevokeModelAuthorization to disable an existing setup.'
                return
            }
        }

        $revision = [Guid]::NewGuid().ToString('N')
        # Stop the old app before removing authorization, even if the next build fails.
        if (-not $key) {
            $existingApps = @(Invoke-Docker ps -q --filter "label=com.docker.compose.project=$project" `
                --filter 'label=com.docker.compose.service=app')
            if ($existingApps) { Invoke-Docker stop @existingApps }
        }
        try { Write-ModelSettings $modelPath $revision $key } finally { $key = $null }
        $modelState = Read-ModelState $modelPath
        if (-not $modelState) { throw 'Could not verify the saved online model configuration.' }
    }
    $env:KA_ONBOARDING_MODEL_REVISION = $modelState.Revision
    $modelRevisionSet = $true

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
    $url = "http://127.0.0.1:$port/"
    $modelStatus = if ($modelState.Enabled) { 'online models authorized' } else { 'online models disabled' }
    Write-Host "Knowledge Assistant is ready at $url ($modelStatus)."
    if (-not $NoBrowser) { Start-Process $url }
} finally {
    if ($locked) {
        if ($minioImageSet) { Remove-Item Env:KA_ONBOARDING_MINIO_IMAGE -ErrorAction SilentlyContinue }
        if ($modelRevisionSet) { Remove-Item Env:KA_ONBOARDING_MODEL_REVISION -ErrorAction SilentlyContinue }
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
    Pop-Location
}
