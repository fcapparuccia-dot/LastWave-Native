package com.lastwave.app.data.radio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.playback.PlayableTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Una stazione FM salvata col cuore.
 *
 * Conserva quanto basta a riavviare lo stream senza rileggere la directory
 * (che in auto può non essere raggiungibile): nome, genere, icona, URL e MIME.
 */
@Serializable
data class RadioFavorite(
    /** Id globale della stazione nella directory. */
    val stationUuid: String,
    val name: String,
    val genre: String = "Radio",
    val countryCode: String? = null,
    val favicon: String? = null,
    val streamUrl: String,
    val mimeType: String? = null,
) {
    fun toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = name,
        artist = genre,
        album = countryCode,
        artworkUrl = favicon,
        playbackUrl = streamUrl,
        playbackMimeType = mimeType,
        durationMs = null,
        radioStationUuid = stationUuid,
    )

    companion object {
        fun fromStation(station: RadioStation) = RadioFavorite(
            stationUuid = station.stationUuid,
            name = station.name,
            genre = station.genreDisplay,
            countryCode = station.countryCode,
            favicon = station.favicon,
            streamUrl = station.playableUrl,
            mimeType = station.mimeType,
        )

        /** Il cuore del player su una stazione: l'id viaggia sul [PlayableTrack]. */
        fun fromTrack(track: PlayableTrack): RadioFavorite? {
            val stationUuid = track.radioStationUuid?.takeIf { it.isNotBlank() } ?: return null
            val streamUrl = track.playbackUrl?.takeIf { it.isNotBlank() } ?: return null
            return RadioFavorite(
                stationUuid = stationUuid,
                name = track.title,
                genre = track.artist,
                countryCode = track.album,
                favicon = track.artworkUrl,
                streamUrl = streamUrl,
                mimeType = track.playbackMimeType,
            )
        }
    }
}

/** Stazione della directory pronta per il player: porta con sé il suo id. */
fun RadioStation.toPlayableTrack(): PlayableTrack = RadioFavorite.fromStation(this).toPlayableTrack()

/**
 * Le stazioni FM col cuore, condivise dalla lista radio e dal cuore del player.
 *
 * Non passano da Liked Songs: una stazione non ha videoId e finiva lì come
 * brano senza URL, quindi il cuore mostrava uno stato che Android Auto non
 * poteva usare.
 */
@Singleton
class RadioFavoritesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    applicationScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _favorites = MutableStateFlow<List<RadioFavorite>>(emptyList())
    val favorites: StateFlow<List<RadioFavorite>> = _favorites.asStateFlow()
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())

    /** Id delle stazioni salvate: il cuore del player legge uno stato sincrono. */
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    init {
        // Il cuore del player deve sapere cosa è già salvato al primo brano,
        // non solo dopo la prima apertura della lista radio.
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data.collect { preferences ->
                publish(decode(preferences[FAVORITES_KEY]))
            }
        }
    }

    suspend fun getAll(): List<RadioFavorite> = decode(dataStore.data.first()[FAVORITES_KEY])

    fun isFavorite(stationUuid: String): Boolean = stationUuid in _favoriteIds.value

    /** Returns true when the station ended up saved. */
    suspend fun toggle(favorite: RadioFavorite): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            var saved = false
            dataStore.edit { preferences ->
                val current = decode(preferences[FAVORITES_KEY])
                val existing = current.any { it.stationUuid == favorite.stationUuid }
                saved = !existing
                val updated = if (existing) {
                    current.filterNot { it.stationUuid == favorite.stationUuid }
                } else {
                    current + favorite
                }
                write(preferences, updated)
            }
            saved
        }

    /** Idempotente: la scheda auto e il doppio tap non devono invertire il cuore due volte. */
    suspend fun set(favorite: RadioFavorite, saved: Boolean): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            dataStore.edit { preferences ->
                val current = decode(preferences[FAVORITES_KEY])
                val updated = if (saved) {
                    if (current.any { it.stationUuid == favorite.stationUuid }) current
                    else current + favorite
                } else {
                    current.filterNot { it.stationUuid == favorite.stationUuid }
                }
                write(preferences, updated)
            }
            saved
        }

    /** Merge idempotente usato dalla migrazione: non tocca le stazioni già salvate. */
    suspend fun addAll(favorites: List<RadioFavorite>) {
        if (favorites.isEmpty()) return
        withContext(Dispatchers.IO + NonCancellable) {
            dataStore.edit { preferences ->
                val merged = (decode(preferences[FAVORITES_KEY]) + favorites)
                    .distinctBy { it.stationUuid }
                write(preferences, merged)
            }
        }
    }

    private fun publish(saved: List<RadioFavorite>) {
        _favorites.value = saved
        _favoriteIds.value = saved.mapTo(mutableSetOf()) { it.stationUuid }
    }

    private fun write(
        preferences: MutablePreferences,
        updated: List<RadioFavorite>,
    ) {
        if (updated.isEmpty()) preferences.remove(FAVORITES_KEY)
        else preferences[FAVORITES_KEY] = json.encodeToString(updated)
    }

    /** A value written by an older build must not hide every station. */
    private fun decode(raw: String?): List<RadioFavorite> =
        raw?.let { runCatching { json.decodeFromString<List<RadioFavorite>>(it) }.getOrNull() }.orEmpty()

    private companion object {
        val FAVORITES_KEY = stringPreferencesKey("lw_radio_favorites")
    }
}
