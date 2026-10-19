# Nocturne on a Fire TV Stick 4K (from a Windows PC)

The `nocturne-fire-stick` branch restyles the launcher (light silver and teal, a "Continue in" hero for the
last app you opened, silver app tiles, a teal focus ring) and sets it up for a Fire TV Stick 4K (2nd gen,
Fire OS 8). Fire OS won't let any app replace Amazon's home screen, so it uses the Fire TV workaround from
the README: when Amazon's home appears, the launcher immediately takes over.

Everything below is done over your Wi-Fi. Nothing is uninstalled, and every step can be undone.

## 1. Turn on ADB on the stick

1. **Settings > My Fire TV > About**, then click your device name (Fire TV Stick 4K) 7 times until it says
   you are a developer.
2. **Settings > My Fire TV > Developer options > ADB debugging: ON.**
3. Note the stick's IP address: **Settings > My Fire TV > About > Network**.

## 2. Set up your PC (once)

1. Install Python 3 from python.org. On the first installer screen, tick **Add python.exe to PATH**.
2. Open PowerShell in this folder (Shift + right-click the folder > Open PowerShell window here), then:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass   # needed once in every new PowerShell window
tools\setup.ps1
tools\tv.ps1 -TvHost 192.168.1.50 -Probe                     # use your stick's IP
```

The TV asks **Allow USB debugging?** the first time. Tick *Always allow from this computer* and press OK,
then run the probe again. It should print the model (AFTKA for the 4K 2nd gen) and Android version.

## 3. Get the app (APK)

Pick one:

- **GitHub, no installs (recommended).** Put this branch on your own GitHub fork, open the **Actions** tab,
  choose **Build APK > Run workflow**, wait about 3 minutes, then download **nocturne-launcher-apk** from the
  finished run and unzip it. You get `app-debug.apk`.
- **Android Studio.** Install Android Studio, open the `launcher` folder, wait for it to sync, then
  **Build > Build App Bundle(s) / APK(s) > Build APK(s)**. After that, `launcher\install.ps1` finds the APK
  by itself.

## 4. Install it

```powershell
launcher\install.ps1 -TvHost 192.168.1.50 -Apk $HOME\Downloads\app-debug.apk
```

Press Home on the remote. Amazon's home flashes for about a second, then Nocturne appears. To reach
Amazon's home on purpose, open the **Amazon Home** tile in the app grid (press Down from the bottom row).

## 5. Optional: switch off Amazon's ads and telemetry

Read `examples\fire-tv-stick-4k-2nd-gen\debloat.txt` first and put a `#` in front of anything you want to
keep. It only lists ad, shopping, telemetry and tutorial packages; streaming apps, casting, Alexa and
updates are left alone. It has not been checked on a real 2nd-gen stick yet, and packages your stick doesn't
have are skipped.

```powershell
tools\disable.ps1 -TvHost 192.168.1.50 -List examples\fire-tv-stick-4k-2nd-gen\debloat.txt
```

## Undo

```powershell
tools\restore.ps1 -TvHost 192.168.1.50 -List examples\fire-tv-stick-4k-2nd-gen\debloat.txt   # if you debloated
tools\tv.ps1 -TvHost 192.168.1.50 -Command "settings delete secure enabled_accessibility_services"
```

The second line switches the Home redirect off (it also switches off any other accessibility service you
had on; `install.ps1` printed what was there before). Then uninstall **Home** from
**Settings > Applications > Manage Installed Applications**.

## Changing what's on it

Edit `launcher\app\src\main\res\values\config.xml`: the bottom-row apps (`dock_apps`), how many tiles per row
(`columns`) and apps to hide. Rebuild and run `install.ps1` again. Package names:
`tools\tv.ps1 -TvHost <ip> -Command "pm list packages"`.

## Known limits

- **Amazon's home flashes briefly** before the redirect. That is a limit of the workaround.
- **Fire OS 8 is untested** by the original author (they tested a Toshiba Fire TV on Fire OS 7). The
  redirect relies on an accessibility service, which Android 11 still allows to open apps, but an Amazon
  update could change that. If Home stops redirecting after an update, set `stock_home_class_hint` to empty
  in `config.xml` and reinstall.
- **After a restart**, check Home still redirects. If not, run `install.ps1` again with the same APK.
- **"Continue in" shows the last app you opened from Nocturne**, not the episode inside it. Showing the
  actual show and episode would need Stremio's account API, and the launcher deliberately has no network
  access.
- The stick has no HDMI inputs, so the Inputs button is hidden (`show_inputs` in `config.xml`).
