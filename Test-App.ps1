$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\LocalEnvironment.ps1')
Use-LocalJava
Start-LocalDatabase
$env:CHAT_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/translate_chat_test'
Push-Location $PSScriptRoot
try {
    & node '--test' '.\tests\translator.test.mjs' '.\tests\message-feed.test.mjs'
    if ($LASTEXITCODE -ne 0) { throw 'Browser message and translation logic tests failed.' }
} finally { Pop-Location }
Invoke-ProjectMaven @('-q', 'test')
