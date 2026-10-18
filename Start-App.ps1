param([int]$AppPort = 8080)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\LocalEnvironment.ps1')
Use-LocalJava
Start-LocalDatabase
$env:PORT = [string]$AppPort
Write-Host "Talklear: http://localhost:$AppPort/ (Ctrl+C で終了 / to stop)"
Invoke-ProjectMaven @('-q', '-DskipTests', 'spring-boot:run')
