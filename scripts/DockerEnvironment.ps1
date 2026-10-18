$ErrorActionPreference = 'Stop'
$script:DockerProjectRoot = Split-Path -Parent $PSScriptRoot
$script:DockerEnvPath = Join-Path $script:DockerProjectRoot '.local\docker.env'

function Assert-ProjectDocker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker Desktop is required. Install it, then reopen PowerShell.'
    }
    $taskEngine = & docker info --format '{{.OSType}}' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop with Linux containers, then retry.' }
    if ($taskEngine -ne 'linux') { throw 'Switch Docker Desktop to Linux containers, then retry.' }
    & docker compose version --short | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is required. Check Docker Desktop.' }
}

function Initialize-ProjectDocker {
    param([ValidateRange(1, 65535)][int]$AppPort = 8080)
    if (Test-Path -LiteralPath $script:DockerEnvPath) {
        $taskSettings = [System.IO.File]::ReadAllText($script:DockerEnvPath)
        $taskMatch = [regex]::Match($taskSettings, '(?m)^CHAT_DOCKER_DB_PASSWORD=([a-f0-9]{64})\r?$')
        if (-not $taskMatch.Success) { throw 'Invalid .local/docker.env. Restore the original settings; do not reset the database.' }
        $taskPassword = $taskMatch.Groups[1].Value
    } else {
        $taskVolumes = & docker volume ls --filter 'name=^translate-chat-local_postgres-data$' --format '{{.Name}}'
        if ($LASTEXITCODE -ne 0) { throw 'Could not check the Docker data volume.' }
        if ($taskVolumes) { throw 'Docker database exists but .local/docker.env is missing. Restore this file; do not reset the database.' }
        $taskBytes = New-Object byte[] 32
        $taskRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $taskRng.GetBytes($taskBytes) } finally { $taskRng.Dispose() }
        $taskPassword = [BitConverter]::ToString($taskBytes).Replace('-', '').ToLowerInvariant()
        New-Item -ItemType Directory -Path (Split-Path -Parent $script:DockerEnvPath) -Force | Out-Null
    }
    $taskSettings = "CHAT_DOCKER_PORT=$AppPort`nCHAT_DOCKER_DB_PASSWORD=$taskPassword`n"
    [System.IO.File]::WriteAllText($script:DockerEnvPath, $taskSettings, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-ProjectCompose {
    param([string[]]$ComposeArguments)
    if (-not (Test-Path -LiteralPath $script:DockerEnvPath)) { throw 'Run .\Start-Docker.ps1 first.' }
    & docker compose --project-name translate-chat-local --project-directory $script:DockerProjectRoot --env-file $script:DockerEnvPath -f (Join-Path $script:DockerProjectRoot 'compose.yaml') @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose exited with code $LASTEXITCODE" }
}
