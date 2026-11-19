# RESUME — pick this work up from another PC

Da qui si riprende il lavoro su un altro PC. Start here when the chat is opened on a different machine.

The repo is synced through OneDrive, so the source is already here: what is missing is the *machine*, not the code. The four steps below rebuild it.

Context and full session state: [docs/side-by-side-debug-build.md](docs/side-by-side-debug-build.md).
House rules for agents: [AGENTS.md](AGENTS.md), [DESIGN.md](DESIGN.md) for UI.

**State at handoff (2026-11-18):** the side-by-side debug build `com.lastwave.app.debug` (blue icon) compiles green — `BUILD SUCCESSFUL`, `exit=0` — and is installed on the phone next to the untouched release app `com.lastwave.app`. The only open item is a real Android Auto run (see the end of this file).

Everything below assumes Windows, PowerShell and a phone on USB with USB debugging authorised.

```powershell
$repo = "$env:USERPROFILE\OneDrive\OneDrive_access\Siti Web\LastWave-Native"
$sdk  = "$env:USERPROFILE\Android\Sdk"                     # NOT necessarily %LOCALAPPDATA% - see below
# JDK 17+. There is no java on PATH and JAVA_HOME is empty on this machine: the JDK is
# C:\Users\fcapp\tools\jdk\jdk-17.0.21+9. Check the usual install roots, then set this by hand if empty.
$java = @(
    (Get-ChildItem "$env:USERPROFILE\tools\jdk" -Directory -ErrorAction SilentlyContinue).FullName
    'C:\Program Files\Android\Android Studio\jbr'
    (Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue).FullName
    (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue).FullName
) | Where-Object { $_ -and (Test-Path "$_\bin\java.exe") } | Select-Object -First 1
$adb  = "$sdk\platform-tools\adb.exe"
```

Run that once per shell; steps 1–4 use those variables. Then confirm the three paths everything else depends on — all must print `True` — and if `$sdk` does not, find the real SDK folder (Android Studio → Settings → SDK) and set `$sdk` to it before going on:

```powershell
Test-Path "$sdk\platform-tools\adb.exe", "$repo\gradlew.bat", "$java\bin\java.exe"
Get-ChildItem "$env:USERPROFILE\Android\Sdk", "$env:LOCALAPPDATA\Android\Sdk" -ErrorAction SilentlyContinue
```

On this machine the SDK lives in `C:\Users\fcapp\Android\Sdk` while `%LOCALAPPDATA%\Android\Sdk` does **not** exist — do not assume either default.

## 1. Hydrate the OneDrive folder

Files-On-Demand turns untouched files into cloud-only placeholders, and both AGP and Gradle fail on them (`AccessDeniedException`, `Unable to delete directory`, `Cannot snapshot …: not a regular file`).

1. Explorer → right-click `LastWave-Native` → **Always keep on this device** → wait until OneDrive stops saying "Syncing".
2. Optionally force every file to download now (slow, a few minutes, but then nothing below can trip on a placeholder):

```powershell
Get-ChildItem $repo -Recurse -File -ErrorAction SilentlyContinue |
    ForEach-Object { try { [IO.File]::OpenRead($_.FullName).Dispose() } catch { } }
```

Do **not** try to detect placeholders by counting `ReparsePoint` attributes: on this machine all 33,483 files carry the reparse tag even when they are fully hydrated and the build is green, so the count says nothing. The reliable test is step 4 itself — if it fails on a snapshot/AccessDenied error that names a file in the OneDrive tree, re-hydrate that folder and rebuild.

## 2. Install the toolchain

JDK 17+ (Android Studio's JBR counts) and these SDK components — 37 is `compileSdk`, 36 is the `audio` module's, NDK/CMake are needed because the audio engine and USB driver are native:

```powershell
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-37.0" "platforms;android-36" "build-tools;37.0.0" "ndk;28.2.13676358" "cmake;3.22.1" "platform-tools"
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

(`latest` may be a versioned folder such as `cmdline-tools\19.0`; if there is no `cmdline-tools` at all, install it from Android Studio → SDK Manager → SDK Tools → "Android SDK Command-line Tools".)

Verify before going on:

```powershell
& "$java\bin\java.exe" -version          # 17 or newer
Get-ChildItem "$sdk\platforms", "$sdk\ndk", "$sdk\cmake" -Directory | Select-Object FullName
& $adb version
```

## 3. Point `local.properties` at this PC's SDK

It syncs through OneDrive but still names the old machine's SDK, and Gradle then stops with `SDK location not found`. The write is guarded, because writing a path that does not exist silently breaks every later build (that is how this file was clobbered with a non-existent `%LOCALAPPDATA%` path once already):

```powershell
if (-not (Test-Path "$sdk\platform-tools")) { throw "No SDK at '$sdk' - fix `$sdk first (step 2)" }
Get-Content "$repo\local.properties" -ErrorAction SilentlyContinue    # keep a note of the old value
Set-Content "$repo\local.properties" "sdk.dir=$($sdk -replace '\\','/')"
Get-Content "$repo\local.properties"                                  # expect sdk.dir=<your path> with forward slashes
```

The file is gitignored, so on a fresh clone it does not exist at all and must be created the same way.

## 4. Build, install, launch

```powershell
Set-Location $repo
$env:JAVA_HOME = $java
$env:ANDROID_HOME = $sdk
$env:PATH = "$java\bin;$sdk\platform-tools;$env:PATH"

.\gradlew.bat --no-daemon -I docs\off-onedrive-build-dir.init.gradle.kts `
    'assembleDebug' '-PapplicationId=com.lastwave.app.debug' '--console=plain'

& $adb uninstall com.lastwave.app.debug          # only needed if an older debug build is installed
& $adb install -r "$env:USERPROFILE\lastwave-build\app\outputs\apk\debug\app-debug.apk"
& $adb shell am start -n com.lastwave.app.debug/com.lastwave.app.MainShellActivity
```

Expected result: `BUILD SUCCESSFUL`, the APK in `%USERPROFILE%\lastwave-build\app\outputs\apk\debug\app-debug.apk` (outside OneDrive on purpose), and both apps installed:

```powershell
& $adb shell pm list packages | Select-String 'lastwave'
& $adb shell dumpsys package com.lastwave.app.debug | Select-String 'versionName|versionCode'
```

The debug icon must be blue and the release icon lime; both apps are named "LastWave", which is the whole point of the side-by-side build.

### If the shell kills long commands

Some agent shells kill a command as soon as it returns no output, which aborts Gradle mid-build. Run it detached and poll the log. The child `powershell.exe` inherits this shell's environment, so export `JAVA_HOME`/`PATH` **before** `Start-Process` (step 4 already did) — a detached script that only sets the location dies with `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`, `exit=9009`:

```powershell
$log = "$env:TEMP\lw-build.log"
$job = "$env:TEMP\lw-build.ps1"
@"
Set-Location '$repo'
& .\gradlew.bat --no-daemon -I docs\off-onedrive-build-dir.init.gradle.kts 'assembleDebug' '-PapplicationId=com.lastwave.app.debug' '--console=plain' *> '$log'
"exit=`$LASTEXITCODE" | Out-File '$log' -Append
"@ | Set-Content $job -Encoding utf8

Start-Process powershell -WindowStyle Hidden -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$job)
Get-Content $log -Tail 5                 # repeat until the line "exit=" appears; 0 means green
```

## Traps already paid for — do not rediscover them

* **Quote Gradle properties**: PowerShell 5.1 turns an unquoted `-PapplicationId=com.lastwave.app.debug` into `-PapplicationId=com` **and** `.lastwave.app.debug`, and Gradle answers `Task '.lastwave.app.debug' not found`. Always `'-PapplicationId=com.lastwave.app.debug'`.
* **Never drop `-I docs/off-onedrive-build-dir.init.gradle.kts`**: without it Gradle writes into `build/` inside OneDrive and dies on identical-content placeholders. Override the output root with `LASTWAVE_BUILD_DIR` if `%USERPROFILE%` is short on space.
* **`Cannot snapshot …\liboboe.so: not a regular file`** means a file in `%USERPROFILE%\.gradle\caches\9.3.1\transforms\` became a OneDrive placeholder. Delete that single transform directory (`d4b07d48…` in the log) and rebuild; it is regenerated. Do not delete the whole `.gradle`.
* **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** = the APK was signed with the other machine's `%USERPROFILE%\.android\debug.keystore`. `adb uninstall com.lastwave.app.debug` first, or copy that keystore across.
* No `release.keystore` travels with the folder: `assembleRelease`/`rawRelease` cannot be signed here. Debug builds need nothing.
* If the copy has no git (plain OneDrive folder): no `git diff`, no `git status` - read files to inspect changes. This checkout does have git. In every case keep EOL as found (the workspace mixes CRLF and LF).

## What is left to do

1. **Real Android Auto run** — the only unverified item. With the blue debug app: search bar query, voice search, "Popular now"/"Recently played" on an empty library, offline fallback in airplane mode, artwork fallback for a track without a cover. Capture with `adb logcat -c` then `adb logcat -s AndroidAutoMediaLibrary MusicPlaybackService* AndroidRuntime Car*` into a file and read it.
2. Decide a `CHANGELOG.md` entry for the `-PapplicationId` property and the debug icon override, and whether to keep the property or move it to the existing `docs/community-daily-discover.init.gradle` `applicationIdSuffix` pattern.
3. Optional: blue PNG `mipmap-*` overrides in `app/src/debug/res/` for the `-PminSdk=24` variant (Android 7/8 uses the legacy PNGs, still lime).
4. Optional: Desktop Head Unit for a visual Auto check without a car — `sdkmanager "extras;google;auto"`, `adb forward tcp:5277 tcp:5277`, then `desktop-head-unit.exe -a`.
5. Optional: make the non-default icon variants (`MainActivityDesktop`/`Black`/`White`) blue in debug too; only the default variant is blue today.

If the car still shows the lime icon, it is showing the **release** app (`com.lastwave.app`, same display name) or a cached bitmap: `adb shell am force-stop com.google.android.projection.gearhead`, and remember `versionCode` is 33 for both apps.
