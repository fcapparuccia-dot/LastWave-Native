package com.lastwave.app.data.radio

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.lastwave.app.data.generate.normalizeTrackText
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.playlist.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sposta una volta sola le stazioni FM che erano state messe col cuore prima
 * che esistessero i preferiti radio.
 *
 * Quei tap passavano da `PlayableTrack.toGeneratedTrack()`, che per uno stream
 * senza videoId scrive una riga in Liked Songs con URL vuoto: una riga che non
 * può essere riprodotta. La stazione si riconosce dall'impronta nome|genere
 * usata dalla lista radio come titolo e artista (la stessa chiave normalizzata
 * di `GeneratedTrack.key`); una riga che non corrisponde a nessuna stazione
 * della directory resta dove sta, per non spostare un episodio omonimo.
 */
@Singleton
class RadioFavoritesMigration @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val radioFavorites: RadioFavoritesRepository,
    private val radioRepository: RadioRepository,
    private val playlistRepository: PlaylistRepository,
    private val applicationScope: CoroutineScope,
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        applicationScope.launch(Dispatchers.IO) {
            // Liked Songs viene creato da un altro avvio in background: qualche
            // tentativo con attesa, poi il prossimo avvio del processo riprova.
            repeat(3) { attempt ->
                val done = runCatching { migrate() }.getOrDefault(false)
                if (done) return@launch
                delay(2_000L * (attempt + 1))
            }
        }
    }

    /** Esposta per il test: una passata. True quando il lavoro è chiuso. */
    internal suspend fun migrate(): Boolean {
        if (dataStore.data.first()[MIGRATED_KEY] == true) return true
        // Liked Songs non ancora pronto: riprova tra poco.
        val liked = runCatching { playlistRepository.getLikedSongs() }.getOrNull() ?: return false
        // Solo cuori su uno stream: niente id YouTube e nessun percorso locale.
        val deadRows = liked.tracks.filter { it.url.isBlank() && it.youtubeVideoIdOrNull() == null }
        if (deadRows.isEmpty()) return markMigrated()
        val stations = runCatching { radioRepository.getItalianStations() }.getOrDefault(emptyList())
        // Directory irraggiungibile: nessun flag, così si riprova.
        if (stations.isEmpty()) return false
        val byKey = stations.associateBy(::stationKey)
        val matchedRows = deadRows.filter { it.key in byKey }
        if (matchedRows.isEmpty()) return markMigrated()
        val moved = matchedRows.mapNotNull { byKey[it.key] }.distinctBy { it.stationUuid }
        radioFavorites.addAll(moved.map(RadioFavorite::fromStation))
        val movedKeys = matchedRows.mapTo(mutableSetOf()) { it.key }
        val remaining = liked.tracks.filterNot { it.key in movedKeys }
        // La playlist è cambiata nel frattempo: i preferiti sono già salvati,
        // un altro tentativo completa la rimozione.
        if (runCatching {
                playlistRepository.replaceTracksForSync(liked.id, remaining, liked.tracks)
            }.getOrNull() == null
        ) {
            return false
        }
        Log.i(TAG, "Moved ${moved.size} FM stations from Liked Songs to Radio preferite")
        return markMigrated()
    }

    private suspend fun markMigrated(): Boolean {
        dataStore.edit { it[MIGRATED_KEY] = true }
        return true
    }

    private fun stationKey(station: RadioStation): String =
        "${station.name.normalizeTrackText()}|${station.genreDisplay.normalizeTrackText()}".lowercase()

    private companion object {
        const val TAG = "RadioFavoritesMigration"
        val MIGRATED_KEY = booleanPreferencesKey("lw_radio_favorites_migrated")
    }
}
