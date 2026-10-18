$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\LocalEnvironment.ps1')
New-Item -ItemType Directory -Path $script:ToolsRoot -Force | Out-Null

function Get-CheckedArchive {
    param([string]$Url, [string]$Destination, [string]$Hash, [string]$Algorithm = 'SHA256')
    if (-not (Test-Path -LiteralPath $Destination)) { Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash.ToLowerInvariant() -ne $Hash.Trim().Split(' ')[0].ToLowerInvariant()) {
        throw "Download checksum mismatch: $Destination"
    }
}

if (-not (Get-ChildItem -LiteralPath $script:ToolsRoot -Directory -Filter 'jdk-21*')) {
    $taskJdk = (Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')[0].binary.package
    $taskArchive = Join-Path $script:ToolsRoot $taskJdk.name
    Get-CheckedArchive $taskJdk.link $taskArchive $taskJdk.checksum
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $script:ToolsRoot -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $script:ToolsRoot 'apache-maven-3.9.16\bin\mvn.cmd'))) {
    $taskUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip'
    $taskArchive = Join-Path $script:ToolsRoot 'apache-maven-3.9.16-bin.zip'
    Get-CheckedArchive $taskUrl $taskArchive ((Invoke-WebRequest -Uri "$taskUrl.sha512" -UseBasicParsing).Content) 'SHA512'
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $script:ToolsRoot -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $script:ToolsRoot 'postgres\bin\pg_ctl.exe'))) {
    $taskUrl = 'https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/18.6.0/embedded-postgres-binaries-windows-amd64-18.6.0.jar'
    $taskArchive = Join-Path $script:ToolsRoot 'postgres-binaries.jar'
    Get-CheckedArchive $taskUrl $taskArchive ((Invoke-WebRequest -Uri "$taskUrl.sha1" -UseBasicParsing).Content) 'SHA1'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskArchive)
    try {
        $taskEntry = $taskZip.GetEntry('postgres-windows-x86_64.txz')
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, (Join-Path $script:ToolsRoot 'postgres.txz'), $true)
    } finally { $taskZip.Dispose() }
    $taskPgDir = Join-Path $script:ToolsRoot 'postgres'
    New-Item -ItemType Directory -Path $taskPgDir -Force | Out-Null
    & tar.exe '-xf' (Join-Path $script:ToolsRoot 'postgres.txz') '-C' $taskPgDir
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL archive extraction failed.' }
}
Use-LocalJava
Invoke-ProjectMaven @('-q', '-DskipTests', 'package')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskAppZip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $script:ProjectRoot 'target\translate-chat-0.1.0.jar'))
try {
    $taskDriver = $taskAppZip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/postgresql-[^/]+\.jar$' } | Select-Object -First 1
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskDriver, (Join-Path $script:ToolsRoot 'postgresql-jdbc.jar'), $true)
} finally { $taskAppZip.Dispose() }
Start-LocalDatabase
Write-Host '準備完了。 .\Start-App.ps1 で起動できます。 / Ready. Run .\Start-App.ps1'
