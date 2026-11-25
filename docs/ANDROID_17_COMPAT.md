# Android 17 (API 37): telefono e Android Auto

Audit fatto il 25/11/2026 leggendo la documentazione Google aggiornata (pagina
*Background audio hardening*, ultimo aggiornamento 2026-10-28) e il codice di
questo repo.

## Verdetto

Con `compileSdk = 37` e `targetSdk = 35` (`app/build.gradle.kts:192` e `:255`)
l'app gira su Android 17 senza alzare il target: **non ci sono cambi obbligatori
di manifest**. Le uniche restrizioni della 17 che valgono anche a target 35 sono
quelle "all apps" e l'app le rispetta già (riproduzione dentro un foreground
service `mediaPlayback`, focus con `AudioFocusRequest` su API 26+, edge-to-edge
già attivo). È stata comunque chiusa una falla di percorso su Android Auto, vedi
*Modifica applicata*.

## Background audio hardening (vale per tutte le app)

> All apps running on Android 17 that have these background audio interactions
> must have a visible activity or must be running a foreground service that is
> not of type `SHORT_SERVICE`. This applies whether or not the app targets API
> level 37.

> If the app tries to call audio APIs while the app is not in a valid lifecycle,
> the audio playback and volume change APIs fail silently without throwing an
> exception or providing a failure message. The audio focus API fails with the
> result code `AUDIOFOCUS_REQUEST_FAILED`.

API colpite: `AudioTrack.write`, AAudio / OpenSL ES / Oboe (quindi anche l'uscita
bit-perfect nativa), `requestAudioFocus`, `set/adjustStreamVolume`,
`setRingerMode`. Solo la richiesta di focus segnala il rifiuto; il resto è
silenzioso.

In più, **solo** per chi targetta API 37 il FGS deve avere capacità
*while-in-use*: avviato mentre l'app era visibile o da un'intenzione esplicita
dell'utente (tap su notifica, widget, tasto multimediale). Con `targetSdk` 35
questo vincolo non si applica; resta valido il primo: senza activity visibile
serve un FGS attivo.

Regola operativa che ne deriva: **nessun frame audio prima che il FGS sia su**.

Stato nel repo:

| Punto | Riferimento | Cosa fa |
|---|---|---|
| Permessi FGS | `AndroidManifest.xml:15-17` | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` |
| Service | `AndroidManifest.xml:263-278` | `exported="true"`, `foregroundServiceType="mediaPlayback"` (riga 269), intent-filter `android.media.browse.MediaBrowserService` (riga 271) |
| Promozione | `MusicPlaybackService.kt:623-665` | `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` su API 29+, retry con notifica minimale, log `"Foreground promotion refused by system"` se il sistema rifiuta |
| Start command | `MusicPlaybackService.kt:686-688` | promuove prima di eseguire l'azione richiesta |
| Voce auto | `MusicPlaybackService.kt:293-296` | `onPlayFromSearch` promuove e poi cerca |
| Tap in auto | `MusicPlaybackServiceCar.kt:173-191` | `playCarMediaId` **ora promuove** prima di suonare (vedi sotto) |
| Notifica | `MusicPlaybackServiceNotification.kt:130-131` | ogni aggiornamento con un brano carico promuove |
| Demote | `MusicPlaybackServiceCar.kt:243-249` | toglie il FGS solo se non c'è brano, non sta suonando e non sta bufferizzando |
| Widget | `widget/wave/WaveWidgetActions.kt:321-323` | `ensurePlaybackService()` = `startForegroundService` senza action → `onStartCommand` promuove |
| Focus | `MusicPlayer.kt:3331-3344` | `AudioFocusRequest.Builder(AUDIO_FOCUS_GAIN)` riusato, rifiuto loggato senza bloccare |
| Spegnimento | `MusicPlaybackService.kt:745-749` | `stopForeground(STOP_FOREGROUND_REMOVE)` solo in `onDestroy` |

## Modifica applicata

`playCarMediaId` avviava la riproduzione da `scope.launch` senza promuovere il
service, mentre `onPlayFromSearch` (riga 294) lo faceva già. È il percorso
principale di Android Auto — tap su un brano con il telefono in tasca — quindi
proprio il caso in cui su Android 17 non c'è né activity visibile né FGS ancora
attivo. Aggiunte due chiamate, sullo stesso pattern dei vicini:

- `promoteForPlayback()` prima del `launch`;
- `demoteIfIdle()` nel ramo `!played`, così un elemento non più disponibile non
  lascia una notifica orfana (stesso comportamento già presente in
  `playCarSearch`).

Nessun altro cambiamento di flusso: la notifica compare un attimo prima, come
già succede per la ricerca vocale.

## Altre modifiche "all apps" della 17

### Limiti di memoria per app

Android 17 limita la memoria per processo in base alla RAM del dispositivo per
fermare i leak. Se l'app viene colpita, `ApplicationExitInfo.reason` è
`REASON_OTHER` e la descrizione contiene `"MemoryLimiter:AnonSwap"`. L'app
dichiara `android:largeHeap="true"` (`AndroidManifest.xml:74`), quindi dopo
sessioni lunghe (player + artwork + EQ nativo) vale la pena leggere quel campo
prima di dare la colpa a un crash nostro. Su build debuggabile si può escludere
una UID: `adb shell am memory-limiter ignore <uid>`.

### Ripristino dell'IME dopo la rotazione

Android 17 non ripristina la tastiera dopo un cambio di configurazione che
l'app non gestisce. `MainShellActivity` gestisce
`orientation|screenSize|keyboardHidden|uiMode` (`AndroidManifest.xml:97`), quindi
l'activity non viene ricreata e la modifica non si applica. Se un giorno si
toglie `configChanges` servirà `windowSoftInputMode="stateAlwaysVisible"` o una
richiesta esplicita di focus.

### Ri-pairing Bluetooth automatico

Riguarda solo app che usano la radio Bluetooth: `ACTION_KEY_MISSING` viene
emesso unicamente se il ri-pairing automatico fallisce e gli extra
(`EXTRA_PAIRING_CONTEXT`) sono per la UI di sistema. LastWave non tocca il
Bluetooth → nulla da fare.

### Nota per Android 18

La 17 è l'ultima versione che accetta `usesCleartextTraffic="true"`
(`AndroidManifest.xml:70`): dalla 18 gli attributi di cleartext vengono ignorati
e contano solo `network_security_config.xml` e i grant URI espliciti. Il file
c'è già, quindi basta non aggiungere nuovi domini `http` fuori da lì.

## Android Auto

Nessun requisito nuovo. I pezzi necessari sono già presenti:
`automotive_app_desc.xml` con `<uses name="media" />`, meta-data
`com.google.android.gms.car.application` (`AndroidManifest.xml:85-87`), service
`exported="true"` con `android.media.browse.MediaBrowserService`
(`AndroidManifest.xml:265` e `:271`), `MediaButtonReceiver`
(`AndroidManifest.xml:280-286`).

Nota per le build sideload: su Android Auto va attivata la modalità sviluppatore
con **Sorgenti sconosciute**, altrimenti l'app non compare nel launcher dell'auto
anche se tutto è dichiarato correttamente.

## Cosa non è stato verificato

Nessuna prova su dispositivo: `adb devices` non elencava telefoni e non è stata
fatta una build. Il cambio su `playCarMediaId` allinea quel percorso al pattern
già in uso, non corregge un bug visto in un log.

## Prova da fare sull'Edge 60 (Android 17)

```powershell
adb devices
adb shell getprop ro.build.version.sdk          # 37
adb shell getconf PAGESIZE                      # 4096 oppure 16384
adb shell dumpsys package com.lastwave.app | Select-String 'versionName|versionCode|targetSdk'
adb logcat -c
```

1. **Schermo spento**: avvia un brano, blocca il telefono, aspetta 2 minuti →
   l'audio continua e la notifica resta.
2. **Auto, app in background**: apri Android Auto con il telefono in tasca,
   sfoglia una playlist e tocca un brano → deve partire subito, senza silenzio
   iniziale. Ripeti con la voce ("riproduci X su LastWave") e con i tasti del
   volante.
3. **Widget**: tap su una playlist del widget mentre è aperta un'altra app.
4. **Bit-perfect USB**: DAC collegato, brano in corso, schermo spento (l'uscita
   nativa Oboe/AAudio ricade nella stessa regola).
5. **Chiusura**: `adb logcat -d | Select-String 'Foreground promotion refused|MusicPlaybackService|MusicPlayer'`
   non deve mostrare `Foreground promotion refused by system` durante un play.
   Poi `adb shell pidof com.lastwave.app` e `adb logcat -b crash` senza nuovi
   FATAL (install con `adb install -r`, vedi `docs/DEBUG_APK_CI_INSTALL.md`).

Se `getconf PAGESIZE` risponde **16384**, le `.so` devono essere allineate a
16 KB: il progetto compila già con `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` e
`-Wl,-z,max-page-size=16384` (`app/build.gradle.kts:271-283`) e `libffmpegJNI.so`
di media3 1.2.1+1 (non allineata) è già esclusa da `FfmpegCompat.isUsable`.

## Se (e quando) si alza `targetSdk`

- **36**: edge-to-edge obbligatorio (già gestito), predictive back di default
  (`enableOnBackInvokedCallback="true"` è già in `AndroidManifest.xml:75`),
  niente opt-out di orientamento/ridimensionamento sulle schermate grandi.
  Google Play richiede il 36 per gli aggiornamenti dal 31/08/2026: finché l'app
  resta a 35 passa solo da sideload/CI, non dal Play Store.
- **37**: il FGS deve avere capacità *while-in-use*. I percorsi auto, widget e
  tasto multimediale sono già "user intent"; andrebbe rivisto solo un play
  avviato da un job o da una sincronizzazione in background.

