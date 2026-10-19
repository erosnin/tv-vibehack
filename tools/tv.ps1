# Windows wrapper for tools\tv.py: check the connection, or run one shell command on the TV/stick over Wi-Fi ADB.
#
# Usage:  tools\tv.ps1 -TvHost 192.168.1.50 -Probe
#         tools\tv.ps1 -TvHost 192.168.1.50 -Command "pm list packages"
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Command,
    [switch]$Probe
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$py = 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'Run tools\setup.ps1 first.' }
$env:TV_HOST = $TvHost
if ($Probe -or -not $Command) {
    & $py -I tools\tv.py --probe
} else {
    & $py -I tools\tv.py $Command
}
exit $LASTEXITCODE
