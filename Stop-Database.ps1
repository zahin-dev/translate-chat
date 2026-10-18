$ErrorActionPreference = 'Stop'
$taskDatabase = Join-Path $PSScriptRoot '.local\postgres'
$taskControl = Join-Path $PSScriptRoot '.tools\postgres\bin\pg_ctl.exe'
if ((Test-Path -LiteralPath $taskControl) -and (Test-Path -LiteralPath (Join-Path $taskDatabase 'PG_VERSION'))) {
    & $taskControl '-D' $taskDatabase 'status' *> $null
    if ($LASTEXITCODE -eq 0) {
        & $taskControl '-D' $taskDatabase '-m' 'fast' '-w' 'stop'
        if ($LASTEXITCODE -ne 0) { throw 'Database shutdown failed.' }
    }
}
