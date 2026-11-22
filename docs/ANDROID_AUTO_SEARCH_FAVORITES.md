# Android Auto: ricerca, radio e preferiti

## Stato del lavoro

Modifiche implementate il 19 novembre 2026 nel progetto LastWave. Il numero di
versione app non è stato incrementato. La compilazione e la prova su Android
Auto **non sono ancora state eseguite**.

## Comportamento aggiunto

- La ricerca Android Auto mostra prima le categorie disponibili: **Albums**,
  **Playlists**, **Radio**; poi mostra i risultati dei singoli brani.
- Album e playlist aprono una pagina con i brani, l'azione
  **Riproduzione casuale** e l'azione **♡ Save to favorites**. Se già salvati,
  l'azione diventa **♥ Remove from favorites**.
- I preferiti sono persistiti localmente in DataStore, separati per tipo e ID
  della raccolta. Il nodo **Favorites** appare nella libreria Android Auto
  quando contiene almeno un elemento; ciascun preferito apre i relativi brani.
- **Radio** apre un'azione che avvia la radio dell'artista usando il flusso già
  esistente in `ArtistRepository`.
- La modifica/rimozione di un preferito notifica Android Auto per aggiornare le
  pagine interessate.

L'azione di salvataggio è una voce dentro l'album o la playlist, non un pulsante
cuore accanto a ogni riga: la UI standard di Android Auto non espone controlli
personalizzati per ciascun elemento sfogliabile.

Quella voce non è riproducibile ma **sfogliabile**: Android Auto trasforma il tap
su una riga riproducibile in una richiesta di riproduzione e porta alla schermata
di riproduzione, per questo il cuore sembrava cambiare pagina. La riga apre un
nodo `favorite-action:` che applica il salvataggio/rimozione e restituisce la
stessa pagina con il cuore già aggiornato (le righe vengono riusate dalla cache
`pageCache`), poi notifica il nodo **Preferiti** e la pagina di partenza.

Sopra i brani, accanto al cuore, ogni pagina di playlist o album espone
**Riproduzione casuale**: avvia l'intera raccolta con `playQueue(startShuffled =
true)` — per le playlist salvate con il loro id, per le raccolte già in cache con
il token dei brani.

## Layout a schede come Spotify

La radice di Android Auto ora espone quattro schede, nello stesso ordine che un
utente Spotify ritrova in auto:

| Scheda | Contenuto (`loadChildren`) |
|---|---|
| **Home** | brano/coda in riproduzione, Liked Songs, **Popolari ora**, **Nuove uscite**, ascoltati di recente, Download, musica locale, **Preferiti** (solo se non vuoto) |
| **Recenti** | playlist avviate davvero (dalla più recente), poi i brani ascoltati di recente |
| **Sfoglia** | gruppi di generi/mood di YouTube Music → generi → playlist del genere → brani |
| **Libreria** | Playlist salvate, Album salvati, Artisti (dalla cronologia di ascolto), Podcast e show |

**Nuove uscite** è un nodo con tre sottomenu, non una lista piatta di brani:

| Voce | Contenuto |
|---|---|
| **Singoli** | uscite singole della griglia `FEmusic_new_releases_albums` |
| **Album** | album completi della stessa griglia |
| **Playlist** | playlist promozionali della pagina nuove uscite |

YouTube Music non ha una pagina dedicata ai singoli: la griglia nuove uscite
mescola album e singoli e li marca tutti con lo stesso
`MUSIC_PAGE_TYPE_ALBUM`, quindi la divisione usa l'etichetta localizzata del
sottotitolo ("Album", "Singolo", "Single", "アルバム"…) e, quando nessuna riga la
espone, l'etichetta maggioritaria della griglia (gli album aprono la lista e sono
i più numerosi). `InnerTubeMusicApi.fetchNewReleasesCollections(24)` scarica in
parallelo la griglia album e la pagina playlist; il risultato resta in cache per
la sessione auto (30 minuti) e ogni voce apre i suoi brani con
`fetchAlbumPage`. Se la rete non risponde entro 10 secondi il nodo resta vuoto
invece di bloccare l'apertura. Il comando vocale "riproduci Nuove uscite" accoda
fino a 4 uscite della griglia, singoli prima degli album.

La cronologia delle playlist non è deducibile da `SongPlayStatsRepository`, che
conosce solo i brani: `RecentPlaylistStore` la registra nel momento in cui una
coda di playlist parte (`MusicPlayer.playQueue(..., playlistId = ...)`), la
deduplica e la limita a 12 voci. Sfoglia e le pagine dei generi sono tenute in
cache (una ora per l'elenco dei gruppi, LRU per generi e playlist) perché ogni
accensione dell'auto riapre gli stessi nodi.

## Pagina artista

**Libreria → Artisti** non avvia più subito la radio: ogni artista apre una
pagina con quattro sezioni e la radio come ultima riga.

| Voce | Contenuto |
|---|---|
| **Brani più popolari** | i brani che YouTube Music ordina per popolarità, fino a 50, con l'etichetta dell'artista come sorgente della coda |
| **Album** | le uscite della shelf Album → brani con `fetchAlbumPage` |
| **Singoli e EP** | le uscite della shelf Singles & EPs, senza ripetere gli album già elencati |
| **Compilation e playlist** | playlist restituite da `searchPlaylists` e filtrate sul nome dell'artista (titolo o autore) → brani con `fetchPlaylist` |
| **Radio dell'artista** | azione che avvia la coda "‹artista› Radio" |

La pagina è risolta una volta per sessione (LRU di 6 artisti) e l'overview
dell'artista parte in parallelo alla ricerca delle playlist, con 15 secondi di
budget complessivo: l'apertura costa un solo giro di rete e i nodi figli sono
immediati. L'overview usa `getArtistDetails(includeFullDiscography = false)`,
così non segue le pagine "more" dell'intera discografia. Se la rete non
risponde la pagina mostra solo la radio, invece di restare vuota.

## Preferiti su ogni raccolta

Il cuore non è più solo nelle pagine aperte dalla ricerca o dalle playlist
salvate: ogni pagina che nomina un intero album o un'intera playlist parte con
la stessa riga **♡ Save to favorites** / **♥ Remove from favorites** sopra
**Riproduzione casuale** e i brani. Sono coperte le uscite dell'artista
(**Album**, **Singoli e EP** → `artist-album:` / `artist-single:`), le
**Compilation e playlist** dell'artista (`artist-playlist:`), le playlist di un
genere in **Sfoglia** (`browse:playlist:`) e le uscite di **Nuove uscite**
(`new-release-album:` / `new-release-playlist:`).

Il tap salva la raccolta con lo stesso tipo della ricerca — `album` per album e
singoli, `playlist` per playlist e compilation — quindi le voci compaiono in
**Album salvati**, nel nodo **Preferiti** e nella scheda **Home**, e si aprono
con i loro brani. La voce da salvare è ricostruita dal nodo di partenza
(`favoriteFor` → `artistReleaseFavorite` / `artistPlaylistFavorite` /
`browsePlaylistFavorite` / `newReleaseFavorite`) dalle stesse cache che hanno
costruito la pagina: il salvataggio non costa una chiamata di rete e non può
salvare un'altra raccolta. Dopo il tap il servizio notifica **Preferiti**,
**Album salvati**, **Libreria**, **Home** e la pagina di partenza, così il cuore
è già rosso e le liste si aggiornano.

## Riga Home

Android Auto mostra le schede dell'app (Home, Recenti, Sfoglia, Libreria) solo
alla radice: dentro un sottomenu resta la freccia indietro, un livello per tap.
Per questo `loadCarChildren` antepone a **ogni** pagina tranne la radice e la
Home una riga **Home** (`category:home`, sfogliabile) che riapre la scheda Home
con un tap. La riga non compare nelle liste telefono che riusano lo stesso
albero (`loadChildren`, usato dalla scheda musicale OriginOS).

La schermata di riproduzione dell'auto non è pilotabile dall'app: una custom
action della sessione non può cambiare la pagina sfogliata dall'host, quindi il
ritorno veloce alla Home è questa riga, raggiungibile con la freccia che
riporta alla pagina di partenza.

## Azione "Artist radio" in riproduzione

`MusicPlaybackServiceSession` pubblica una custom action
`ACTION_ARTIST_RADIO` ("Artist radio", icona `ic_car_artist_radio`) subito dopo
il cuore, così i due pulsanti esterni di Android 13+ sono "mi piace" e "radio
dell'artista". Il servizio la gestisce in `MusicPlaybackServiceCar.kt`
(`playCarArtistRadio`), che riusa `AndroidAutoMediaLibrary.playArtistRadio`
(lo stesso percorso della voce **Artisti** della Libreria) e mostra un errore
di riproduzione se la radio non produce brani. L'azione resta sempre
selezionabile: riavvia la radio dell'artista del brano corrente.

Il pulsante si accende quando la radio è attiva: `ic_car_artist_radio` è la
radio *outlined* bianca (spenta), `ic_car_artist_radio_filled` la radio piena
nel colore `car_action_active` (accento). Lo stato "acceso" si deduce
dall'etichetta della coda — `artistRadioLabel(artista)` in
`playback/ArtistRadioQueue.kt` costruisce l'unica forma usata da tutti i
percorsi ("‹artista› Radio") e `MusicPlayerState.isArtistRadioQueue` la
riconosce — non dall'artista del brano corrente, che nella radio cambia
canzone dopo canzone. L'etichetta entra anche nella firma di
`publishSystemState`, così il MediaSession viene ripubblicato (e l'icona
ridisegnata dall'host) appena la coda diventa una radio o smette di esserlo.



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
3. Verificare su auto/host che le voci **Save to favorites** /
   **Remove from favorites** e **Start radio** siano mostrate e attivino
   l'azione prevista **senza lasciare la pagina** della playlist o dell'album.
   Se l'host mostra ancora la vecchia riga riproducibile (albero in cache),
   riconnettere Android Auto. L'interfaccia può variare in base all'host.
4. Verificare che il nodo **Favorites** si aggiorni dopo salvataggio e
   rimozione, e che album e playlist salvati riaprano i brani corretti.
5. Verificare che la riga **Riproduzione casuale** compaia sopra i brani di
   playlist e album e avvii l'intera raccolta in ordine casuale (icona shuffle
   attiva nella sessione).
6. Verificare che la radice mostri le quattro schede **Home**, **Recenti**,
   **Sfoglia**, **Libreria** e che le voci di ogni scheda aprano il livello
   atteso; che **Recenti** si popoli solo dopo aver avviato una playlist.
7. Verificare che la custom action **Artist radio** compaia in riproduzione e
   avvii una coda "‹artista› Radio" senza toccare il cuore.
8. Verificare che in **Libreria → Artisti** ogni artista apra la pagina con
   **Brani più popolari**, **Album**, **Singoli e EP**, **Compilation e
   playlist** e **Radio dell'artista**, e che le righe dei brani avviino la
   coda con l'etichetta dell'artista, dell'album o della playlist.
9. Aprire un album e una compilation da **Libreria → Artisti** e verificare che
   la prima riga sia **♡ Save to favorites**, che il tap la trasformi in
   **♥ Remove from favorites** senza lasciare la pagina, e che la raccolta
   compaia in **Album salvati** / **Preferiti** e si riapra con i suoi brani.
10. Verificare che ogni pagina dei sottomenu (Libreria, Artisti, album,
    compilation, Sfoglia, Nuove uscite, coda **In riproduzione**) parta con la
    riga **Home** e che il tap riporti alla scheda Home, e che nelle liste della
    scheda musicale OriginOS la riga Home non compaia.

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
