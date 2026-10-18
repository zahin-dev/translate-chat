$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:ToolsRoot = Join-Path $script:ProjectRoot '.tools'
$script:LocalRoot = Join-Path $script:ProjectRoot '.local'

function Use-LocalJava {
    $taskJdk = Get-ChildItem -LiteralPath $script:ToolsRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
    $taskMaven = Join-Path $script:ToolsRoot 'apache-maven-3.9.16'
    if (-not $taskJdk -or -not (Test-Path -LiteralPath (Join-Path $taskMaven 'bin\mvn.cmd'))) {
        throw '先に .\Setup.ps1 を実行してください。 / Run .\Setup.ps1 first.'
    }
    $env:JAVA_HOME = $taskJdk.FullName
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path $taskMaven 'bin') + ';' + $env:PATH
    $env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
}

function Start-LocalDatabase {
    $taskBin = Join-Path $script:ToolsRoot 'postgres\bin'
    $taskData = Join-Path $script:LocalRoot 'postgres'
    $taskPasswordFile = Join-Path $script:LocalRoot 'database-password.txt'
    New-Item -ItemType Directory -Path $script:LocalRoot -Force | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $taskBin 'pg_ctl.exe'))) {
        throw '先に .\Setup.ps1 を実行してください。 / Run .\Setup.ps1 first.'
    }
    if (-not (Test-Path -LiteralPath $taskPasswordFile)) {
        if (Test-Path -LiteralPath (Join-Path $taskData 'PG_VERSION')) { throw 'DBのパスワードファイルがありません。データを初期化せず、設定を確認してください。' }
        $taskBytes = New-Object byte[] 32
        $taskRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $taskRng.GetBytes($taskBytes) } finally { $taskRng.Dispose() }
        [System.IO.File]::WriteAllText($taskPasswordFile, [Convert]::ToBase64String($taskBytes))
    }
    $env:CHAT_DB_PASSWORD = [System.IO.File]::ReadAllText($taskPasswordFile).Trim()
    $env:CHAT_DB_USER = 'chat'
    $env:CHAT_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/translate_chat'
    if (-not (Test-Path -LiteralPath (Join-Path $taskData 'PG_VERSION'))) {
        & (Join-Path $taskBin 'initdb.exe') '-D' $taskData '-U' 'chat' '--auth=scram-sha-256' '--encoding=UTF8' '--locale=C' "--pwfile=$taskPasswordFile"
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed.' }
    }
    & (Join-Path $taskBin 'pg_ctl.exe') '-D' $taskData 'status' *> $null
    if ($LASTEXITCODE -ne 0) {
        & (Join-Path $taskBin 'pg_ctl.exe') '-D' $taskData '-l' (Join-Path $script:LocalRoot 'postgres.log') '-o' '-h 127.0.0.1 -p 55432' '-w' 'start'
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed. Check .local\postgres.log.' }
    }
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-cp' (Join-Path $script:ToolsRoot 'postgresql-jdbc.jar') (Join-Path $script:ProjectRoot 'scripts\DatabaseSetup.java')
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL database preparation failed.' }
}

function Invoke-ProjectMaven {
    param([string[]]$MavenArguments)
    Push-Location $script:ProjectRoot
    try {
        & (Join-Path $script:ToolsRoot 'apache-maven-3.9.16\bin\mvn.cmd') "-Dmaven.repo.local=$script:ToolsRoot\m2" @MavenArguments
        if ($LASTEXITCODE -ne 0) { throw "Maven exited with code $LASTEXITCODE" }
    } finally { Pop-Location }
}
