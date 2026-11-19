# Side-by-side debug build — session log, 2026-11-18

Working notes so the session can be resumed without re-deriving anything. Environment: Windows, repo inside OneDrive (the notes were written in a plain copy without `.git`; this checkout does have git), device reachable through `C:\Users\fcapp\scrcpy\adb.exe`.

Goal of the session: install a **debug** build next to the release app so Android Auto, the dynamic app icon and the Cast provider can be exercised without wiping the release app's data, and make the debug app's icon visibly different (blue) from the release one (lime `#FFC9FB00`).

## 1. Changes in the repo

Two edits, nothing else. Release behaviour is unchanged.

### 1.1 `app/src/main/AndroidManifest.xml` — no edit: the provider keeps its fixed class name

The session pointed this meta-data at `${applicationId}.playback.cast.LastWaveCastOptionsProvider`. That is
wrong and was dropped: `LastWaveCastOptionsProvider` is a plain class compiled under the module `namespace`
(`com.lastwave.app`, `app/build.gradle.kts`), which does **not** follow `applicationId`, and
`CastContext.getSharedInstance` resolves the value with `Class.forName()` — an absolute name, never relative
to the app package. A side-by-side (`com.lastwave.app.debug`) or vivo (`com.tencent.qqmusic`) build would
look up `com.lastwave.app.debug.playback.…`; the `ClassNotFoundException` is swallowed by
`MusicPlayer.initializeCastImpl`'s `runCatching`, so Cast silently stops initialising on exactly the builds
this section is about.

```xml
<meta-data
    android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
    android:value="com.lastwave.app.playback.cast.LastWaveCastOptionsProvider" />
```

The `FileProvider` two hundred lines below does use `${applicationId}.provider`, but that is a component
*authority*, which Android never resolves as a class name. `@Keep` on the provider preserves the name
through R8, and no `-adaptclassstrings` / `-repackageclasses` rule touches it.

### 1.2 `app/build.gradle.kts` (defaultConfig) — optional `-PapplicationId`

```kotlin
applicationId = (project.findProperty("applicationId") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: if (vivoOrigin) "com.tencent.qqmusic" else "com.lastwave.app"
```

Default unchanged (`com.lastwave.app`, or `com.tencent.qqmusic` with `-PvivoOrigin=true`). Needed because a debug APK cannot `adb install -r` over a release APK signed with a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

> Note: the repo already documents an alternative for the same purpose — `docs/community-daily-discover.init.gradle` applies `applicationIdSuffix = '.communitytest'` from an init script, without touching build files. Open decision in §6.

### 1.3 `app/src/debug/res/values/colors.xml` — new file, debug-only blue icon

```xml
<resources>
    <color name="ic_launcher_background">#FF2979FF</color>
</resources>
```

`app/src/main/res/values/colors.xml` still has the lime `#FFC9FB00`, so release stays lime. In debug every adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`) picks up the blue background, and with it the launcher, App Info, the notification artwork fallback and Android Auto. Revert = delete the file.

## 2. Building and installing

Toolchain: JDK 17 `C:\Users\fcapp\tools\jdk\jdk-17.0.21+9`, SDK `C:\Users\fcapp\Android\Sdk` (build-tools 37.x `aapt2`), artifact at `C:\Users\fcapp\lastwave-build\app\outputs\apk\debug\app-debug.apk`.

The repo lives in OneDrive: Files-On-Demand dehydrates generated files into read-only placeholders (reparse tag `0x9000e01a`) and AGP then dies with `AccessDeniedException` on `generateDebugBuildConfig` or "Unable to delete directory" on `packageDebug`. Every module's build directory is redirected outside the synced tree by `docs/off-onedrive-build-dir.init.gradle.kts`, which is in the repo so it travels with it (`LASTWAVE_BUILD_DIR` overrides the location, default `<home>/lastwave-build`):

```kotlin
val outRoot = java.io.File(
    System.getenv("LASTWAVE_BUILD_DIR")
        ?: System.getProperty("lastwave.buildDir")
        ?: java.io.File(System.getProperty("user.home"), "lastwave-build").path,
)

gradle.beforeProject {
    val rel = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
    layout.buildDirectory.set(java.io.File(outRoot, rel))
}
```

This machine additionally has a pinned copy at `C:\Users\fcapp\tools\outOfOneDrive.init.gradle.kts` (hardcoded `C:/Users/fcapp/lastwave-build`, used by `build.ps1`); the two are interchangeable, and on this machine they resolve to the same output directory. The repo copy was validated with a real build: `BUILD SUCCESSFUL in 1m 35s`, `exit=0`, 76 tasks `UP-TO-DATE` in `C:\Users\fcapp\lastwave-build` (23:06).

Build (≈90 s cold, exit 0). `C:\Users\fcapp\tools\build.ps1` wraps this — it sets the toolchain env, `cd`s into the repo and forwards its arguments to Gradle, logging to `build.log`:

```powershell
$env:JAVA_HOME = 'C:\Users\fcapp\tools\jdk\jdk-17.0.21+9'
$env:ANDROID_HOME = 'C:\Users\fcapp\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
Set-Location 'C:\Users\fcapp\OneDrive\OneDrive_access\Siti Web\LastWave-Native'
$log = 'C:\Users\fcapp\tools\build.log'
"start $(Get-Date -Format o) args=$($args -join ' ')" | Out-File $log -Encoding utf8
& .\gradlew.bat -I 'C:\Users\fcapp\tools\outOfOneDrive.init.gradle.kts' @args 2>&1 | Out-File $log -Append -Encoding utf8
"exit=$LASTEXITCODE done $(Get-Date -Format o)" | Out-File $log -Append -Encoding utf8
```

Invocation used this session (`build.ps1 assembleDebug -PapplicationId=com.lastwave.app.debug --console=plain`), i.e. the equivalent direct call:

```powershell
C:\Users\fcapp\OneDrive\...\LastWave-Native\gradlew.bat --no-daemon `
  -I docs\off-onedrive-build-dir.init.gradle.kts `
  'assembleDebug' '-PapplicationId=com.lastwave.app.debug' '--console=plain'
```

Note the quotes around `-PapplicationId=…`: PowerShell 5.1 splits an unquoted `-Pname=value.with.dots` into `-Pname=value` **and** `.with.dots`, which Gradle then treats as a task name (`Task '.lastwave.app.debug' not found in root project 'LastWave'`). Verified with a two-line batch echo: `RAW=[assembleDebug -PapplicationId=com .lastwave.app.debug --console=plain]`.

Install + launch (also re-checks `versionName/versionCode` and that `MusicPlaybackService` is exported for the car host):

```powershell
C:\Users\fcapp\scrcpy\adb.exe install -r C:\Users\fcapp\lastwave-build\app\outputs\apk\debug\app-debug.apk
C:\Users\fcapp\scrcpy\adb.exe shell am start -n com.lastwave.app.debug/com.lastwave.app.MainShellActivity
```

### The workflow's debug APK (CI)

`.github/workflows/build-debug-apk.yml` builds `assembleDebug` with `-PapplicationId=com.lastwave.app.debug` and uploads
`final_apk/LastWave-v<version>-debug.apk`, `SHA256SUMS.txt` and `SIGNER.txt` (the `apksigner verify --print-certs` output).

The job restores one cached `~/.android/debug.keystore` (cache key `lastwave-ci-debug-keystore-v1`), so successive CI
artifacts share a key and update each other. That key is **not** this machine's `%USERPROFILE%\.android\debug.keystore`,
so the first CI APK needs one uninstall of the side-by-side app.

`actions/cache` saves a path only if it still exists when the job ends, and the first run of this job ended with
`Path Validation Error: Path(s) specified in the action for caching do(es) not exist, hence no cache is being saved` —
a miss that could never become a hit, i.e. the bug this cache exists to fix. The job therefore creates the keystore
itself on a cache miss (`keytool -genkeypair`, alias `androiddebugkey`, store/key password `android`, the same values
AGP uses), so a restored key and a generated key are interchangeable.

`SIGNER.txt` carries both sides of the check: the `apksigner` `V2 Signer: certificate SHA-256 digest` of the APK and a
normalised `keystore sha256=…` line of the cached key. If those two differ, the APK is signed by a key that never
reaches the cache and the next run's APK will not update it.

That is what the first fully-working run showed: AGP 9 signs the debug variant with a keystore of its own, so the two
digests differed and the APK changed key every run. The staging step therefore re-signs the APK with the cached key
(`apksigner sign --ks ~/.android/debug.keystore`, alias `androiddebugkey`, password `android`) before publishing it.

A key mismatch is what an OEM installer shows as *"package not installed because it conflicts with an existing package
on the device"* (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): it reads like a leftover in Settings, and it survives a reboot.
Check what is really installed — also for other users, because app clones, Secure Folder/Secure Mode and work profiles
keep their own copy:

```powershell
& C:\Users\fcapp\scrcpy\adb.exe shell pm list packages | Select-String 'lastwave|qqmusic'
& C:\Users\fcapp\scrcpy\adb.exe shell pm list users
& C:\Users\fcapp\scrcpy\adb.exe shell cmd package list packages --user 0 | Select-String 'lastwave'
& C:\Users\fcapp\scrcpy\adb.exe uninstall com.lastwave.app.debug
```

`SIGNER.txt` in the artifact is the ground truth for the key already on the phone: compare it with
`adb shell dumpsys package com.lastwave.app.debug | Select-String signatures`.

Debug builds are labelled **LastWave Debug** — on `<application>` and on all five launcher aliases
(`app/src/debug/AndroidManifest.xml`, string in `app/src/debug/res/values/strings.xml`) — so the side-by-side copy is no
longer indistinguishable from the release app on the home screen and in Settings › Apps.

### Resuming from another PC

What travels with the OneDrive folder: all source, including both changes in Â§1 and both `docs/` scripts. So on the other machine the blue debug icon and the optional `-PapplicationId` property are already in place as soon as OneDrive finishes syncing.

What does **not** travel and has to be recreated there:

| Missing | Fix on the new machine |
| --- | --- |
| JDK 17 | JDK 17 or newer. On this machine it is `C:\Users\fcapp\tools\jdk\jdk-17.0.21+9` — there is neither `java` on `PATH` nor a `JAVA_HOME`, so the variable must be exported explicitly (Android Studio is not installed here, `…\Android Studio\jbr` does not exist). |
| Android SDK components | `sdkmanager "platforms;android-37.0" "platforms;android-36" "build-tools;37.0.0" "ndk;28.2.13676358" "cmake;3.22.1" "platform-tools"` — 37 for `compileSdk 37`, 36 for the `audio` module, build-tools for `aapt2`, NDK/CMake because the audio engine and USB driver are native, platform-tools for `adb`. |
| `local.properties` | It syncs but still says `sdk.dir=C:/Users/fcapp/Android/Sdk`. Point it at the new SDK or Gradle fails with "SDK location not found". (Gitignored, so a fresh clone has none at all.) |
| Gradle 9.3.1 distribution | Wrapper jar is in the folder; the distribution downloads on the first run (network), or copy `%USERPROFILE%\.gradle\wrapper\dists`. |
| `C:\Users\fcapp\tools\*`, `C:\Users\fcapp\scrcpy\adb.exe` | Not needed: use `<SDK>\platform-tools\adb.exe` and the commands in §2/§4 directly, with the repo's `docs/off-onedrive-build-dir.init.gradle.kts`. |
| `release.keystore` | Not in the folder (gitignored). Debug builds need nothing; `assembleRelease`/`rawRelease` cannot be signed there. |

Two more traps:

* The debug APK is signed with the machine-local `%USERPROFILE%\.android\debug.keystore`, so installing a build made on the second PC over the debug app already on the phone fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: `adb uninstall com.lastwave.app.debug` first, or copy the keystore across.
* Make sure the folder is fully hydrated before the first build (Explorer → right-click the folder → "Always keep on this device"), otherwise `gradlew` itself can be a dehydrated placeholder.

Whole flow on the new machine — the three paths at the top are the only things to edit:

```powershell
$repo = "$env:USERPROFILE\OneDrive\OneDrive_access\Siti Web\LastWave-Native"
$sdk  = "$env:USERPROFILE\Android\Sdk"    # here: C:\Users\fcapp\Android\Sdk. NOT %LOCALAPPDATA%\Android\Sdk, which does not exist on this machine
$java = 'C:\Users\fcapp\tools\jdk\jdk-17.0.21+9'   # JDK 17; there is no java on PATH and JAVA_HOME is empty here
$adb  = "$sdk\platform-tools\adb.exe"

$env:JAVA_HOME = $java
$env:ANDROID_HOME = $sdk
$env:PATH = "$java\bin;$sdk\platform-tools;$env:PATH"
if (-not (Test-Path "$sdk\platform-tools")) { throw "No SDK at '$sdk'" }
Set-Content "$repo\local.properties" "sdk.dir=$($sdk -replace '\\', '/')"

& "$repo\gradlew.bat" --no-daemon -I "$repo\docs\off-onedrive-build-dir.init.gradle.kts" `
    'assembleDebug' '-PapplicationId=com.lastwave.app.debug' '--console=plain'
& $adb uninstall com.lastwave.app.debug        # only if yesterday's debug build is there
& $adb install -r "$env:USERPROFILE\lastwave-build\app\outputs\apk\debug\app-debug.apk"
& $adb shell am start -n com.lastwave.app.debug/com.lastwave.app.MainShellActivity
```

### Verification performed this session (all green)

| Check | Command | Result |
| --- | --- | --- |
| Debug APK resource value | `aapt2 dump resources app-debug.apk` → `color/ic_launcher_background` | `#ff2979ff` (release still `#ffc9fb00`) |
| Icon actually resolved by the system | `aapt2 dump badging app-debug.apk` | `application-icon-160/240/320/480/640:'res/mipmap-anydpi-v26/ic_launcher.xml'` |
| Adaptive icon background | `aapt2 dump xmltree app-debug.apk --file res/mipmap-anydpi-v26/ic_launcher.xml` | `background=@0x7f0600ab` = blue color above (same for `ic_launcher_round.xml`) |
| Installed package | `adb install -r` | `Success`; `com.lastwave.app.debug` starts `MainShellActivity`, no `FATAL EXCEPTION` in `logcat -b crash` |
| Icon on device | screenshot of Settings → App Info | blue icon, lime replaced |
| Release untouched | `app/src/main/res/values/colors.xml` | unchanged, lime |

## 3. Android Auto and the app icon (settled)

Android Auto has no icon of its own; every surface it paints comes from resources already covered by the debug override:

* **App list / now-playing app icon** — `PackageManager.getApplicationIcon`, i.e. `res/mipmap-anydpi-v26/ic_launcher.xml`. The default launcher alias `.MainActivity` (`android:enabled="true"`, `android:icon="@mipmap/ic_launcher"`) uses the same resource, so blue in debug.
* **Artwork fallback for tracks without album art** — `MusicPlaybackServiceNotification.kt` uses `R.mipmap.ic_launcher` in `setImageViewResource`, same adaptive icon.
* **Browse-tree item decorations** — `AndroidAutoMediaLibrary.appIconUri()` points at `drawable/ic_car_attribution`, four white bars (`#FFFFFFFF`, verified in the APK). It is also the `TintableAttributionIcon`, so it must stay white: the car tints it.

No further change is needed for "blue in Android Auto". If the car still shows lime, it is the **release** app entry (`com.lastwave.app` also installs as "LastWave") or Gearhead's icon cache keyed on `versionCode`, which is still 33:

```powershell
C:\Users\fcapp\scrcpy\adb.exe shell am force-stop com.google.android.projection.gearhead   # then reconnect
```

`pm clear com.google.android.projection.gearhead` also works but drops paired cars.

## 4. Local tooling (`C:\Users\fcapp\tools`, outside the repo)

All of these are re-creatable from the snippets above/below; they only exist to survive the shell being killed between tool calls (see §5).

| File | Purpose |
| --- | --- |
| `build.ps1` | Sets `JAVA_HOME`/`ANDROID_HOME`, `cd`s into the repo, runs `gradlew.bat -I outOfOneDrive.init.gradle.kts @args`, writes `start … args=…` / `exit=…` into `build.log`. |
| `install-debug.ps1` | `adb install -r` the debug APK, dumps `versionName/versionCode/firstInstallTime` and the `MusicPlaybackService` export state, launches `MainShellActivity`; log `install.log`. |
| `verify.ps1` | `pidof`, resumed activity, `logcat -b crash` filtered for `lastwave`, last `AndroidAutoMediaLibrary` lines; log `verify2.log`. |
| `verify.ps1` (older `verify.log`) / `progress.ps1` / `watch.ps1` | Poll `build.log` for the `exit=` line and echo the tail into `progress.log` / `watch.log` — used to await a detached build. |
| `icon-check.ps1` | `aapt2 dump resources` filtered on `ic_launcher_background` (`icon-check.log`). |
| `auto-icon-check.ps1` | `aapt2 dump xmltree` of both adaptive icons, the manifest, and `ic_car_attribution` (`auto-icon-check.log`). |
| `badging.ps1` | `aapt2 dump badging` filtered on `package:`/`application-icon`/`application:` (`badging.log`). |
| `shot.ps1` | Opens App Info for `com.lastwave.app.debug`, `screencap`, pulls to `lwicon.png` (`shot.log`) — the screenshot that shows the blue icon. |
| `capture-logcat.ps1` | `logcat -c` then streams `AndroidAutoMediaLibrary`, `MusicPlaybackService*`, `AndroidRuntime`, `Car*` into `auto-logcat.txt`. |
| `outOfOneDrive.init.gradle.kts` | Build-dir redirect, pinned to `C:/Users/fcapp/lastwave-build`. The portable equivalent is in the repo: `docs/off-onedrive-build-dir.init.gradle.kts`. |

Logs and evidence from this session: `build.log`, `install.log`, `verify.log`, `verify2.log`, `icon-check.log`, `auto-icon-check.log`, `badging.log`, `shot.log`, `lwicon.png` (blue icon screenshot).

## 5. Environment gotchas

* The interactive shell is killed when a command returns, so long jobs must run detached and be polled from their log file:
  `Start-Process powershell -WindowStyle Hidden -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','C:\Users\fcapp\tools\build.ps1','assembleDebug','-PapplicationId=com.lastwave.app.debug','--console=plain')`
  then watch `build.log` for the `exit=` line. A foreground command never returns a usable exit code here.
* OneDrive placeholders break AGP (see §2) — never remove the `-I` init script, and keep both the build dir and `%USERPROFILE%\.gradle` outside OneDrive.
* **The Gradle user home can dehydrate too.** At 23:00 a build died on six tasks at once with `Cannot snapshot C:\Users\fcapp\.gradle\caches\9.3.1\transforms\d4b07d48...\workspace\transformed\oboe-1.10.0\prefab\modules\oboe\libs\android.arm64-v8a\liboboe.so: not a regular file`. The file had become a cloud placeholder (`fsutil reparsepoint query` → tag `0x9000601a`, attribute `ReparsePoint`); reading it back did **not** clear the attribute and Gradle 9 still refuses to snapshot it. Fix: `Remove-Item -Recurse -Force` that single transform dir and rebuild — it is regenerated as a normal file and the tasks return to `UP-TO-DATE`. Nothing repo-side was wrong.
* **`ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`, `exit=9009`** in a detached job: there is no `java` on PATH on this machine and `JAVA_HOME` is empty, so the child `powershell.exe` finds nothing. Export `JAVA_HOME` (and prepend `%JAVA_HOME%\bin` to `PATH`) in the launching shell — `build.ps1` does it on line 1, a hand-written here-string does not.
* **PowerShell 5.1 mangles `-Pname=value.with.dots`**: always single-quote Gradle properties (`'-PapplicationId=com.lastwave.app.debug'`), otherwise the tail after the first dot becomes a task name. The helper `build.ps1` is immune because it splats an `@args` array, but hand-typed commands are not.
* Not a git clone: there is no `git diff`/`git status`. Inspect edits by reading the file, and check EOL by bytes (the tree mixes CRLF and LF; the files created in this session are LF).
* `aapt2` is authoritative for APK contents, not the source tree: `(Get-ChildItem 'C:\Users\fcapp\Android\Sdk\build-tools\*\aapt2.exe' | Sort-Object FullName -Descending | Select-Object -First 1).FullName`.
* `versionCode` stays 33 for both apps, so anything caching by package+version (Gearhead icons, launcher shortcuts) can keep an old bitmap.
* Default `minSdk` is 29; `-PminSdk=24` builds the legacy variant, which uses the PNG `mipmap-*` files that are still lime in debug.

## 6. To do tomorrow

0. On a different machine the entry point is [`RESUME.md`](../RESUME.md) at the repo root — steps 1–4 there (hydrate OneDrive, toolchain, `local.properties`, build+install) then come back here from point 1. Also `adb uninstall com.lastwave.app.debug` before installing a build made there.
1. **Real Android Auto run** (the only thing still unverified): connect the car and test with the blue debug app — search bar query, voice search, "Popular now"/"Recently played" on an empty library, offline fallback in airplane mode, artwork fallback for a track without cover. Capture with `capture-logcat.ps1`, read `auto-logcat.txt`.
2. Decide whether the `-PapplicationId` property (1.2) and the debug icon override (1.3) deserve a `CHANGELOG.md` entry, and whether to keep 1.2 or switch to the existing init-script pattern (`docs/community-daily-discover.init.gradle`, `applicationIdSuffix`) and revert `app/build.gradle.kts`.
3. Optional: blue PNG `mipmap-*` overrides in `app/src/debug/res/` for the `-PminSdk=24` variant (Android 7/8 launchers ignore the adaptive icon).
4. Optional: install the Desktop Head Unit for a visual Auto check without a car — `sdkmanager "extras;google;auto"`, then `adb forward tcp:5277 tcp:5277` and run `desktop-head-unit.exe -a`.
5. Optional: make the non-default icon variants (`MainActivityDesktop`/`Black`/`White`) blue in debug too; only the default variant (`.MainActivity`, active now) is blue.

Not verified at all: the app was never run against a real car head unit in this session, and the debug build was exercised only on the phone.

## 7. CI APK onto the phone — verified 2026-11-19

What was actually run end to end on a motorola edge 60 (`ZY22LJS7N3`), after run 3 of the workflow
(`e1c3834d`, `SIGNER.txt`: APK `V3.0 Signer … 100396dc…` and `keystore sha256=100396dc…`):

1. `adb uninstall com.lastwave.app.debug` — **one time only**, because the copy on the phone had been built
   with this machine's debug key. The log had `signatures:[75953fef]`, the CI APK is `100396dc…`, so
   `adb install -r` had already failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
   com.lastwave.app.debug signatures do not match newer version`. Users live under two ids (`0` owner,
   `10` Vault Profile); the app was only in `0`, so a plain uninstall was enough — a work profile needs
   `adb uninstall --user 10 com.lastwave.app.debug` too.
2. `adb install` (not `-r`) then reported `Success`, and
   `dumpsys package com.lastwave.app.debug | grep signatures` became
   `version:3, signatures:[238704e3]`.
3. `adb pull /data/app/…/base.apk` and a SHA-256 of it **equals** the artifact's `SHA256SUMS.txt`
   (`2E833EC0…E535`): a streamed install stores the APK byte for byte, so the package on the phone is the
   file CI published, signed with the cached key.
4. The app starts (process `com.lastwave.app.debug` up, first screen is the runtime-permission grant).

The installed signer hash (`238704e3`) is PackageManager's own 32-bit digest of the certificate, not the
certificate's SHA-256 — compare it only with itself across installs, never with `SIGNER.txt`.

