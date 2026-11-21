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

## Layout a schede come Spotify

La radice di Android Auto ora espone quattro schede, nello stesso ordine che un
utente Spotify ritrova in auto:

| Scheda | Contenuto (`loadChildren`) |
|---|---|
| **Home** | brano/coda in riproduzione, Liked Songs, **Popolari ora**, **Nuove uscite**, ascoltati di recente, Download, musica locale, **Preferiti** (solo se non vuoto) |
| **Recenti** | playlist avviate davvero (dalla più recente), poi i brani ascoltati di recente |
| **Sfoglia** | gruppi di generi/mood di YouTube Music → generi → playlist del genere → brani |
| **Libreria** | Playlist salvate, Album salvati, Artisti (dalla cronologia di ascolto), Podcast e show |

**Nuove uscite** riusa la stessa shelf della Home del telefono: gli album di
`getCachedFeed().newReleases` (o `InnerTubeMusicApi.fetchNewReleases()` quando
la cache è vuota) vengono appiattiti nei loro brani da
`NewReleasesRepository.fetchPreview()`, senza toccare la coda di paging usata
dalla schermata del telefono. La lista è limitata a 24 brani da 4 album, con un
budget di rete di 10 secondi e cache per sessione auto di 30 minuti; se la rete
non risponde, il nodo resta vuoto invece di bloccare l'apertura.

La cronologia delle playlist non è deducibile da `SongPlayStatsRepository`, che
conosce solo i brani: `RecentPlaylistStore` la registra nel momento in cui una
coda di playlist parte (`MusicPlayer.playQueue(..., playlistId = ...)`), la
deduplica e la limita a 12 voci. Sfoglia e le pagine dei generi sono tenute in
cache (una ora per l'elenco dei gruppi, LRU per generi e playlist) perché ogni
accensione dell'auto riapre gli stessi nodi.

## Azione "Artist radio" in riproduzione

`MusicPlaybackServiceSession` pubblica una custom action
`ACTION_ARTIST_RADIO` ("Artist radio", icona `ic_car_artist_radio`) subito dopo
il cuore, così i due pulsanti esterni di Android 13+ sono "mi piace" e "radio
dell'artista". Il servizio la gestisce in `MusicPlaybackServiceCar.kt`
(`playCarArtistRadio`), che riusa `AndroidAutoMediaLibrary.playArtistRadio`
(lo stesso percorso della voce **Artisti** della Libreria) e mostra un errore
di riproduzione se la radio non produce brani. L'azione resta sempre
selezionabile: riavvia la radio dell'artista del brano corrente.



- `app/src/main/java/com/lastwave/app/playback/AndroidAutoMediaLibrary.kt` —
  radice a quattro schede, ricerca per categorie, pagine di album/playlist/radio,
  Sfoglia per generi, Libreria (playlist, album salvati, artisti, podcast),
  riproduzione e navigazione dei preferiti.
- `app/src/main/java/com/lastwave/app/data/playlist/RecentPlaylistStore.kt` —
  persistenza DataStore delle playlist avviate, deduplicate e limitate a 12.
- `app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt` —
  `playQueue(..., playlistId = ...)` registra la playlist nella cronologia.
- `app/src/main/java/com/lastwave/app/playback/MusicPlaybackService.kt` —
  gestione della nuova azione `ACTION_ARTIST_RADIO`.
- `app/src/main/java/com/lastwave/app/playback/MusicPlaybackServiceCar.kt` —
  notifiche di aggiornamento dopo le azioni sui preferiti e avvio della radio
  dell'artista dal volante.
- `app/src/main/java/com/lastwave/app/playback/MusicPlaybackServiceSession.kt` —
  custom action "Artist radio" accanto al cuore.
- `app/src/main/res/drawable/ic_car_artist_radio.xml` — icona dell'azione.
- `app/src/main/java/com/lastwave/app/data/playlist/CarMediaFavorites.kt` —
  modello e persistenza DataStore.
- `app/src/test/java/com/lastwave/app/playback/AndroidAutoMediaLibrarySearchTest.kt` —
  test di ordine dei risultati, salvataggio di un album, schede della radice e
  avvio radio.
- `app/src/test/java/com/lastwave/app/data/playlist/RecentPlaylistStoreTest.kt` —
  test di deduplicazione, copertina di ripiego e limite di 12 voci.
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
5. Verificare che la radice mostri le quattro schede **Home**, **Recenti**,
   **Sfoglia**, **Libreria** e che le voci di ogni scheda aprano il livello
   atteso; che **Recenti** si popoli solo dopo aver avviato una playlist.
6. Verificare che la custom action **Artist radio** compaia in riproduzione e
   avvii una coda "‹artista› Radio" senza toccare il cuore.

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
