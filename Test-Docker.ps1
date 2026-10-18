$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\DockerEnvironment.ps1')
Assert-ProjectDocker
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required to run the Docker smoke test.' }
if (-not (Test-Path -LiteralPath $script:DockerEnvPath)) { throw 'Run .\Start-Docker.ps1 first.' }
$taskSettings = [System.IO.File]::ReadAllText($script:DockerEnvPath)
$taskPort = [regex]::Match($taskSettings, '(?m)^CHAT_DOCKER_PORT=([0-9]+)\r?$')
if (-not $taskPort.Success) { throw 'CHAT_DOCKER_PORT is missing from .local/docker.env.' }
$taskPreviousUrl = $env:CHAT_SMOKE_BASE_URL
$taskPreviousRecreate = $env:CHAT_SMOKE_RECREATE
Push-Location $PSScriptRoot
try {
    $env:CHAT_SMOKE_BASE_URL = "http://localhost:$($taskPort.Groups[1].Value)"
    $env:CHAT_SMOKE_RECREATE = '1'
    & node '--test' 'tests/docker-smoke.test.mjs'
    if ($LASTEXITCODE -ne 0) { throw 'Docker smoke test failed.' }
} finally {
    $env:CHAT_SMOKE_BASE_URL = $taskPreviousUrl
    $env:CHAT_SMOKE_RECREATE = $taskPreviousRecreate
    Pop-Location
}
