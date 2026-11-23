# APK debug: build in CI e installazione sul telefono

Procedura unica per generare un APK debug aggiornato e installarlo sul telefono
**senza disinstallare** l'app. Pensata per qualsiasi agente/chat: i comandi sono
quelli verificati il 22 novembre 2026 su `motorola_edge_60` (`ZY22LJS7N3`).

## Regole in breve

- Il workflow `.github/workflows/build-debug-apk.yml` è **solo `workflow_dispatch`**:
  un `git push` **non** avvia la build. Va lanciato via API (o dalla UI GitHub).
- L'APK è firmato con `%USERPROFILE%\.android\debug.keystore`, presa dal secret
  di repository **`DEBUG_KEYSTORE_BASE64`**. Così si installa sopra la build
  locale con `adb install -r`, senza perdere i dati.
- Installare **sempre** con `adb install -r`. **Mai** `adb uninstall` senza il
  consenso esplicito dell'utente: cancella i dati dell'app debug (impostazioni,
  statistiche, download, playlist locali).
- Mai stampare il token GitHub: recuperarlo con `git credential fill` e usarlo
  solo in memoria o in un file temporaneo cancellato subito dopo.

## Prerequisiti

| Cosa | Come ottenerlo / verificarlo |
|---|---|
| `adb` | `C:\Users\fcapp\scrcpy\adb.exe` (altrove: `where.exe adb`) |
| Telefono | `adb devices -l` → `ZY22LJS7N3 … model:motorola_edge_60` |
| Token GitHub | `git credential fill` per `host=github.com`, con `GIT_TERMINAL_PROMPT=0` e `GCM_INTERACTIVE=never` (nessun prompt); scope `repo` |
| Secret firma | GitHub → Settings › Secrets and variables › Actions → `DEBUG_KEYSTORE_BASE64` (base64 di `%USERPROFILE%\.android\debug.keystore`). Se manca, la run fallisce allo step *Write the debug signing key from the repository secret*: è voluto, meglio di un APK con chiave sbagliata |
| Fallback: ri-firma locale | `C:\Users\fcapp\Android\Sdk\build-tools\37.0.0\apksigner.bat` + JDK `C:\Users\fcapp\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2` (`JAVA_HOME`) |

## 1. Avviare la build

Il workflow legge il codice del branch su cui viene lanciato: normalmente `main`.
Il commit deve essere già su `origin/main`.

```powershell
$repo = 'fcapparuccia-dot/LastWave-Native'
$env:GIT_TERMINAL_PROMPT = '0'; $env:GCM_INTERACTIVE = 'never'
$token = ((("protocol=https`nhost=github.com`n`n" | git credential fill) -split "`n") |
  Where-Object { $_ -like 'password=*' } | Select-Object -First 1).Substring(9).Trim()
$headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json';
  'X-GitHub-Api-Version' = '2022-11-28'; 'User-Agent' = 'lastwave-ci' }
$body = @{ ref = 'main'; inputs = @{ application_id = 'com.lastwave.app.debug' } } |
  ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Headers $headers -ContentType 'application/json' -Body $body `
  -Uri "https://api.github.com/repos/$repo/actions/workflows/build-debug-apk.yml/dispatches"
```

La dispatch risponde `204` e non restituisce l'id della run: prendilo dalla lista.

## 2. Seguire la run

```powershell
$runs = Invoke-RestMethod -Headers $headers `
  -Uri "https://api.github.com/repos/$repo/actions/workflows/build-debug-apk.yml/runs?per_page=5"
$run = $runs.workflow_runs[0]
"run={0} status={1} conclusion={2} sha={3}" -f $run.id, $run.status, $run.conclusion, $run.head_sha
```

- Build tipica: **5–8 minuti** (Gradle + C++), poi i test del car.
- Dettaglio degli step: `…/actions/runs/<id>/jobs` (campo `steps[].status`).
- Log completo (scarica un file, da leggere in coda): `…/actions/jobs/<job_id>/logs`.
- La run è valida solo con `status=completed` e `conclusion=success`; controlla
  nel log `BUILD SUCCESSFUL` e le righe di `SIGNER.txt` (vedi §3).

## 3. Scaricare l'artifact

Nome: **`LastWave-v<version>-debug-APK`** (es. `LastWave-v5.2.3-debug-APK`).
Contiene `LastWave-v<version>-debug.apk`, `SHA256SUMS.txt` e `SIGNER.txt`; il file
`.apk` pesa ~144 MB (l'artifact compresso ~51 MB: è normale).

Il download ha due passaggi obbligati: la `archive_download_url` risponde `302`
verso un blob Azure firmato, che rifiuta una seconda forma di autenticazione.
Quindi si risolve il redirect **con** il token, poi si scarica la URL firmata
**senza** il token.

```powershell
$dir = Join-Path $env:TEMP 'lw-ci-apk'; New-Item -ItemType Directory -Force $dir | Out-Null
$a = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repo/actions/runs/$($run.id)/artifacts"
$art = $a.artifacts | Where-Object { $_.name -like '*-debug-APK' } | Select-Object -First 1
$hdr = Join-Path $dir 'hdr.txt'
[IO.File]::WriteAllText($hdr, "Authorization: Bearer $token`r`n")
$resp = Join-Path $dir 'resp.txt'
& curl.exe -sS -D $resp -o NUL -H ("@" + $hdr) $art.archive_download_url
$signed = ((Get-Content $resp | Where-Object { $_ -match '^location:' } | Select-Object -First 1) -split ':', 2)[1].Trim()
$zip = Join-Path $dir 'artifact.zip'
& curl.exe -sS -L -o $zip $signed
Remove-Item $hdr -Force
Expand-Archive -Path $zip -DestinationPath (Join-Path $dir 'apk') -Force
Get-ChildItem (Join-Path $dir 'apk') -Filter *.apk
Get-Content (Join-Path $dir 'apk\SIGNER.txt')
```

`SIGNER.txt` deve mostrare lo stesso digest nelle due righe:

```text
V3.0 Signer: certificate SHA-256 digest: 05e3717a…   <- firma dell'APK
keystore sha256=05e3717a…                            <- chiave del secret
```

Se differiscono, il workflow nuovo ferma il job: non installare quell'APK.

## 4. Installare e verificare

```powershell
$adb = 'C:\Users\fcapp\scrcpy\adb.exe'
$apk = Get-ChildItem (Join-Path $dir 'apk') -Filter *.apk | Select-Object -First 1
& $adb install -r $apk.FullName          # -r: aggiorna in loco, dati conservati
& $adb shell am start -n com.lastwave.app.debug/com.lastwave.app.MainShellActivity
```

Verifica (tutti i valori devono tornare):

```powershell
$path = (& $adb shell pm path com.lastwave.app.debug).Trim().Replace('package:','')
& $adb shell ls -l $path                                  # size = size dell'APK scaricato
& $adb shell dumpsys package com.lastwave.app.debug | Select-String 'versionName|lastUpdateTime|signatures=|ceDataInode'
& $adb shell pidof com.lastwave.app.debug                 # processo vivo
& $adb logcat -b crash -d -t 40 | Select-String 'lastwave|FATAL'   # nessun FATAL nuovo
```

`ceDataInode` (utente 0) deve essere **lo stesso** letto prima dell'installazione:
è la prova che i dati non sono stati toccati. `signatures:[…]` è un hash 32-bit di
PackageManager: confrontalo solo con se stesso tra installazioni, non con il
digest SHA-256 di `SIGNER.txt`.

## 5. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

Significa che l'APK è firmato con una chiave diversa da quella dell'app
installata. Diagnosi:

```powershell
# base.apk installato e APK scaricato: confronta i certificati
& $adb pull $path "$env:TEMP\installed.apk"
$env:JAVA_HOME = 'C:\Users\fcapp\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
& 'C:\Users\fcapp\Android\Sdk\build-tools\37.0.0\apksigner.bat' verify --print-certs "$env:TEMP\installed.apk"
& 'C:\Users\fcapp\Android\Sdk\build-tools\37.0.0\apksigner.bat' verify --print-certs $apk.FullName
```

- **Causa più comune**: il secret `DEBUG_KEYSTORE_BASE64` non è la chiave con cui
  è stata firmata la build installata (es. keystore locale rigenerata). Allinea
  il secret e rilancia una run:
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore"))`
  → Settings › Secrets › Actions → aggiorna `DEBUG_KEYSTORE_BASE64`.
- **Fix immediato senza toccare la CI**: ri-firma l'APK scaricato con la chiave
  del telefono e installa.

```powershell
Copy-Item $apk.FullName "$env:TEMP\resigned.apk" -Force
& 'C:\Users\fcapp\Android\Sdk\build-tools\37.0.0\apksigner.bat' sign `
  --ks "$env:USERPROFILE\.android\debug.keystore" --ks-key-alias androiddebugkey `
  --ks-pass pass:android --key-pass pass:android "$env:TEMP\resigned.apk"
& $adb install -r "$env:TEMP\resigned.apk"
```

- **Non disinstallare** (`adb uninstall com.lastwave.app.debug`) senza il
  consenso dell'utente: è l'ultima spiaggia e azzera i dati dell'app debug.
  Con un work profile o un clone serve anche `--user 10`.

## 6. Pulizia

Cancella i file che contengono il token (`hdr.txt`) e, se non servono,
`$env:TEMP\resigned.apk` / `$env:TEMP\installed.apk`. L'APK scaricato può
restare: serve a reinstallare senza riscaricare.

## Stato verificato — 22 novembre 2026

- run `45027268860` sul commit `991a2d15`: `conclusion=success`, `SIGNER.txt` con
  `05e3717a…` su entrambe le righe (guard dei digest superato) e test del car verdi;
- artifact installato **così com'è** (senza ri-firma): `adb install -r` → `Success`,
  `versionName 5.2.3`, `signatures:[75953fef]` invariata, `ceDataInode 580969`
  invariato (dati conservati), app avviata senza nuovi crash;
- la vecchia chiave della CI (`100396dc…`) è storia: non servono più né il re-sign
  manuale né l'uninstall per aggiornare il telefono.

Contesto e cronologia della firma: [`docs/side-by-side-debug-build.md`](side-by-side-debug-build.md) §6 e §7.

## Stato verificato — 23 novembre 2026

- run `45196399454` sul commit `504e897e`: `conclusion=success`, step *Car library
  unit tests* verde, `SIGNER.txt` con `05e3717a…` sulle due righe e hash dell'APK
  uguale a `SHA256SUMS.txt` (`cc63fbc1…`);
- installato **così com'è** sul `motorola_edge_60` (`ZY22LJS7N3`): `adb install -r`
  → `Success`, `base.apk` della stessa dimensione del file scaricato (143818164
  byte), `versionName 5.2.3`, `signatures:[75953fef]` e `ceDataInode 580969`
  invariati (dati conservati), `MainShellActivity` in primo piano e nessun FATAL
  nuovo nel buffer crash;
- su quel PC `adb` non era installato: `platform-tools` (adb 37.0.3) scaricato in
  `%TEMP%\platform-tools`. Al primo collegamento il telefono risulta `unauthorized`
  finché non si accetta il popup «Consenti debug USB» (spunta «Ricorda»).