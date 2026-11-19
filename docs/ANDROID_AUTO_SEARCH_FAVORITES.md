# Android Auto: ricerca, radio e preferiti

## Stato del lavoro

Modifiche implementate il 19 novembre 2026 nel progetto LastWave. Il numero di
versione app non è stato incrementato. La compilazione e la prova su Android
Auto **non sono ancora state eseguite**.

## Comportamento aggiunto

- La ricerca Android Auto mostra prima le categorie disponibili: **Albums**,
  **Playlists**, **Radio**; poi mostra i risultati dei singoli brani.
- Album e playlist aprono una pagina con i brani e l'azione
  **♡ Save to favorites**. Se già salvati, l'azione diventa
  **♥ Remove from favorites**.
- I preferiti sono persistiti localmente in DataStore, separati per tipo e ID
  della raccolta. Il nodo **Favorites** appare nella libreria Android Auto
  quando contiene almeno un elemento; ciascun preferito apre i relativi brani.
- **Radio** apre un'azione che avvia la radio dell'artista usando il flusso già
  esistente in `ArtistRepository`.
- La modifica/rimozione di un preferito notifica Android Auto per aggiornare le
  pagine interessate.

L'azione di salvataggio è una voce selezionabile dentro l'album o la playlist,
non un pulsante cuore accanto a ogni riga: la UI standard di Android Auto non
espone controlli personalizzati per ciascun elemento sfogliabile.

## File interessati

- `app/src/main/java/com/lastwave/app/playback/AndroidAutoMediaLibrary.kt` —
  ricerca per categorie, pagine di album/playlist/radio, riproduzione e
  navigazione dei preferiti.
- `app/src/main/java/com/lastwave/app/playback/MusicPlaybackServiceCar.kt` —
  notifiche di aggiornamento dopo le azioni sui preferiti.
- `app/src/main/java/com/lastwave/app/data/playlist/CarMediaFavorites.kt` —
  modello e persistenza DataStore.
- `app/src/test/java/com/lastwave/app/playback/AndroidAutoMediaLibrarySearchTest.kt` —
  test di ordine dei risultati, salvataggio di un album e avvio radio.
- `app/src/test/java/com/lastwave/app/data/playlist/CarMediaFavoritesTest.kt` —
  test di persistenza, deduplicazione e rimozione.

## Verifiche ancora necessarie

1. Eseguire i test e compilare su un PC con JDK 17 e Android SDK configurati.
2. Installare il nuovo APK sul telefono e verificare ricerca di album, playlist
   e artisti in Android Auto.
3. Verificare su auto/host che le voci selezionabili **Save to favorites**,
   **Remove from favorites** e **Start radio** siano mostrate e attivino
   l'azione prevista. L'interfaccia Android Auto può variare in base all'host.
4. Verificare che il nodo **Favorites** si aggiorni dopo salvataggio e
   rimozione, e che album e playlist salvati riaprano i brani corretti.

I test non sono stati eseguiti: su questo PC `java`/`javac`, Android Studio,
Android SDK e `adb` non risultavano disponibili. L'installazione di Temurin JDK
17 è stata annullata da Windows. `local.properties` indica
`C:/Users/fcapp/Android/Sdk`, percorso che non è presente in questo ambiente;
configurare il percorso SDK corretto sul PC di build, senza riutilizzare alla
cieca il file locale.

L'APK già presente in `app/build/outputs/apk/debug/app-debug.apk` è datato
18 novembre 2026, quindi precede queste modifiche e **non va usato per provarle**.

## Build e installazione su un PC Windows configurato

Aprire PowerShell nella cartella radice del progetto ed eseguire:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.lastwave.app.data.playlist.CarMediaFavoritesTest" --tests "com.lastwave.app.playback.AndroidAutoMediaLibrarySearchTest"
.\gradlew.bat :app:assembleDebug
```

Il nuovo APK debug viene generato in:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Con il telefono collegato e il debug USB autorizzato:

```powershell
adb devices -l
adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

`adb install -r` aggiorna l'app mantenendo i dati solo se package name e
certificato di firma sono compatibili con la versione installata. Se Android
rifiuta l'aggiornamento per una firma diversa, non disinstallare l'app prima di
aver deciso come preservarne i dati.
