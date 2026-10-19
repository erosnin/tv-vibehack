# Windows version of tools/restore.sh: re-enable every package in a debloat list, point Home back at the stock
# launcher and reset animation scales. Pass the same list you used with tools\disable.ps1.
#
# Usage:  tools\restore.ps1 -TvHost 192.168.1.50 -List examples\fire-tv-stick-4k-2nd-gen\debloat.txt
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [Parameter(Mandatory = $true)][string]$List,
    [string]$Stock = 'com.amazon.tv.launcher'
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
    if ($pkg) { "pm enable $pkg" }
}
$cmds += @(
    "pm enable $Stock",
    "cmd package set-home-activity --user 0 $Stock",
    'settings put global window_animation_scale 1.0',
    'settings put global transition_animation_scale 1.0',
    'settings put global animator_duration_scale 1.0',
    'pm list packages -d'
)
$env:TV_HOST = $TvHost
$cmds | & $py -I tools\tv.py --batch
} finally {
    Pop-Location
}
