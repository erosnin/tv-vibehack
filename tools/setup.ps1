# Windows: one-time setup. Creates tools\venv and installs the small ADB-over-Wi-Fi client (adb-shell).
# No Android SDK or adb.exe needed for installing; only Python 3.9+ from python.org.
#
# Usage (PowerShell, from the repo folder):   powershell -ExecutionPolicy Bypass -File tools\setup.ps1
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {

if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 -m venv tools\venv
} else {
    & python -m venv tools\venv
}
if ($LASTEXITCODE -ne 0 -or -not (Test-Path tools\venv\Scripts\python.exe)) {
    throw 'Could not create tools\venv. Install Python 3 from python.org and tick "Add python.exe to PATH".'
}
& tools\venv\Scripts\python.exe -m pip install --upgrade pip
& tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'pip install failed.' }
Write-Host 'Done. Next: tools\tv.ps1 -TvHost <stick ip> -Probe   (accept the "Allow USB debugging" prompt on the TV)'
} finally {
    Pop-Location
}
