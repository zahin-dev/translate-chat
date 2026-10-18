$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\DockerEnvironment.ps1')
Assert-ProjectDocker
Invoke-ProjectCompose @('down')
Write-Host 'Stopped. Conversation history and .local/docker.env are kept.'
