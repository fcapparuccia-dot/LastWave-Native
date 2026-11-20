package com.lastwave.app.data.playlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One playlist that was actually started, newest first. */
@Serializable
data class RecentPlaylist(
    val id: Long,
    val title: String,
    val artworkUrl: String? = null,
    val playedAtMillis: Long = 0L,
)

/**
 * Recently played playlists for the car's "Recenti" tab.
 *
 * The play-stats table only knows songs, so playlist recency has to be
 * recorded where playback starts instead of being derived later. The list is
 * capped: a car screen shows a handful of rows, and stale entries past that
 * are worth less than a bounded DataStore write on every queue start.
 */
@Singleton
class RecentPlaylistStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAll(): List<RecentPlaylist> =
        decode(dataStore.data.first()[RECENT_KEY])
            .sortedByDescending { it.playedAtMillis }

    /** Moves [playlist] to the top: playing it twice never lists it twice. */
    suspend fun record(playlist: SavedPlaylist) {
        if (playlist.title.isBlank()) return
        dataStore.edit { preferences ->
            val entry = RecentPlaylist(
                id = playlist.id,
                title = playlist.title,
                artworkUrl = playlist.coverArtworkUrl(),
                playedAtMillis = System.currentTimeMillis(),
            )
            val updated = (listOf(entry) + decode(preferences[RECENT_KEY]).filterNot { it.id == entry.id })
                .take(MAX_RECENT_PLAYLISTS)
            preferences[RECENT_KEY] = json.encodeToString(updated)
        }
    }

    /** A value written by an older build must not poison the whole tab. */
    private fun decode(raw: String?): List<RecentPlaylist> =
        raw?.let { runCatching { json.decodeFromString<List<RecentPlaylist>>(it) }.getOrNull() }.orEmpty()

    private fun SavedPlaylist.coverArtworkUrl(): String? =
        customCoverUri?.takeIf { it.isNotBlank() }
            ?: remoteArtworkUrl?.takeIf { it.isNotBlank() }
            ?: tracks.firstNotNullOfOrNull { track -> track.artworkUrl?.takeIf { it.isNotBlank() } }

    companion object {
        private const val MAX_RECENT_PLAYLISTS = 12
        private val RECENT_KEY = stringPreferencesKey("lw_recent_playlists")
    }
}
