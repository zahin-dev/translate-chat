param(
    [ValidateRange(1, 65535)][int]$AppPort = 8080,
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\DockerEnvironment.ps1')
Assert-ProjectDocker
if (-not $PSBoundParameters.ContainsKey('AppPort') -and (Test-Path -LiteralPath $script:DockerEnvPath)) {
    $taskSavedPort = [regex]::Match([System.IO.File]::ReadAllText($script:DockerEnvPath), '(?m)^CHAT_DOCKER_PORT=([0-9]+)\r?$')
    if (-not $taskSavedPort.Success -or [int]$taskSavedPort.Groups[1].Value -lt 1 -or [int]$taskSavedPort.Groups[1].Value -gt 65535) {
        throw 'Invalid saved Docker port in .local/docker.env.'
    }
    $AppPort = [int]$taskSavedPort.Groups[1].Value
}
Initialize-ProjectDocker -AppPort $AppPort
$taskArguments = @('up', '-d')
if ($NoBuild) { $taskArguments += '--no-build' } else { $taskArguments += '--build' }
Invoke-ProjectCompose $taskArguments

$taskUrl = "http://localhost:$AppPort/"
$taskReady = $false
Add-Type -AssemblyName System.Net.Http
$taskHandler = New-Object System.Net.Http.HttpClientHandler
$taskHandler.UseProxy = $false
$taskClient = New-Object System.Net.Http.HttpClient($taskHandler)
$taskClient.Timeout = [TimeSpan]::FromSeconds(2)
try {
    for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
        try {
            $taskResponse = $taskClient.GetAsync($taskUrl).GetAwaiter().GetResult()
            try {
                if ([int]$taskResponse.StatusCode -eq 200) { $taskReady = $true; break }
            } finally { $taskResponse.Dispose() }
        } catch { Start-Sleep -Seconds 1 }
    }
} finally {
    $taskClient.Dispose()
}
if (-not $taskReady) {
    throw 'App did not become ready. Check: docker compose --env-file .local/docker.env logs --tail 80 app'
}
Write-Host "Talklear ready: $taskUrl"
Write-Host 'Stop with .\Stop-Docker.ps1. Conversation history is kept in the Docker volume.'
