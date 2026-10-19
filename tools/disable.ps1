# Windows version of tools/disable.sh: disable every package in a debloat list (one per line, # comments).
# Packages are disabled for user 0, never uninstalled. Undo with tools\restore.ps1 and the same list.
#
# Usage:  tools\disable.ps1 -TvHost 192.168.1.50 -List examples\fire-tv-stick-4k-2nd-gen\debloat.txt
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [Parameter(Mandatory = $true)][string]$List
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$listPath = (Resolve-Path $List).Path
Push-Location $repo
try {
$py = 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'Run tools\setup.ps1 first.' }

$cmds = foreach ($line in Get-Content $listPath) {
    $pkg = ($line -replace '#.*$', '').Trim()
    if ($pkg) { "pm disable-user --user 0 $pkg" }
}
$cmds += 'pm list packages -d'
$env:TV_HOST = $TvHost
$cmds | & $py -I tools\tv.py --batch
} finally {
    Pop-Location
}
