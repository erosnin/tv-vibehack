# Windows: install the Nocturne launcher on a Fire TV stick and switch on the Home redirect.
#
#   launcher\install.ps1 -TvHost 192.168.1.50 -Apk C:\Users\you\Downloads\app-debug.apk
#
# -Apk      an APK built by GitHub Actions (README, "Nocturne on a Fire TV Stick") or Android Studio.
#           Left out: uses launcher\app\build\outputs\apk\debug\app-debug.apk, building it first with
#           gradlew.bat when an Android SDK is installed (Android Studio puts one in %LOCALAPPDATA%\Android\Sdk).
# -NoRedirect  install only; Home keeps opening Amazon's home screen (open "Home" from Your Apps instead).
#
# Undo: tools\tv.ps1 -TvHost <ip> -Command "settings delete secure enabled_accessibility_services"
#       (if nothing else used accessibility), then uninstall "Home" from Settings > Applications.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Apk,
    [switch]$NoRedirect
)
$ErrorActionPreference = 'Stop'
$apkPath = if ($Apk) { (Resolve-Path $Apk).Path } else { $null }
Push-Location $PSScriptRoot
try {
$py = '..\tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'Run tools\setup.ps1 first.' }
$env:TV_HOST = $TvHost

if (-not $apkPath) {
    $apkPath = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apkPath)) {
        if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        if (-not (Test-Path $env:ANDROID_HOME)) {
            throw "No APK given and no Android SDK at $env:ANDROID_HOME. Pass -Apk, or install Android Studio."
        }
        $jbr = 'C:\Program Files\Android\Android Studio\jbr'
        if (-not $env:JAVA_HOME -and (Test-Path $jbr)) { $env:JAVA_HOME = $jbr }
        if (-not (Test-Path local.properties)) {
            $sdk = $env:ANDROID_HOME -replace '\\', '\\' -replace ':', '\:'
            Set-Content -Path local.properties -Value "sdk.dir=$sdk" -Encoding ASCII
        }
        & .\gradlew.bat assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    }
}

Write-Host "Sending $apkPath to the TV..."
& $py -I ..\tools\b64push.py $apkPath /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'Copy to the TV failed.' }
@(
    # Each GitHub Actions build is signed with a fresh debug key, so an update can be refused with
    # INSTALL_FAILED_UPDATE_INCOMPATIBLE. Then the old copy is removed and the new one installed.
    'pm install -r /data/local/tmp/tvhome.apk || { pm uninstall com.gathin.tvhome; pm install /data/local/tmp/tvhome.apk; }',
    'rm -f /data/local/tmp/tvhome.apk',
    'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
    'appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow; echo overlay-allowed'
) | & $py -I ..\tools\tv.py --batch

if (-not $NoRedirect) {
    # Turn on the accessibility service that sends Amazon's Home to this launcher (adb's shell may change
    # secure settings itself).
    $svc = 'com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService'
    $cur = (& $py -I ..\tools\tv.py 'settings get secure enabled_accessibility_services' | Out-String).Trim()
    if (-not $cur -or $cur -eq 'null') { $new = $svc }
    elseif ($cur -like "*$svc*") { $new = $cur }
    else { $new = "${cur}:$svc" }
    # Clear first: a reinstalled service only rebinds (and re-reads its config) after being switched off and on.
    @(
        'settings delete secure enabled_accessibility_services',
        "settings put secure enabled_accessibility_services $new",
        'settings put secure accessibility_enabled 1'
    ) | & $py -I ..\tools\tv.py --batch
    $before = if ($cur) { $cur } else { 'null' }
    Write-Host "Home redirect on. Accessibility services before this: $before"
}
Write-Host 'Installed. Press Home on the remote.'
} finally {
    Pop-Location
}
