package com.lastwave.app.data.playlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lastwave.app.data.generate.GeneratedTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The car's "Recenti" tab reads this store: order, dedup and the cap matter. */
class RecentPlaylistStoreTest {
    @Test
    fun recordingAPlaylistTwiceKeepsOneNewestFirstEntry() = runTest {
        val store = MemoryPreferences()
        val recents = RecentPlaylistStore(store)

        recents.record(savedPlaylist(id = 1L, title = "Car mix"))
        recents.record(savedPlaylist(id = 2L, title = "Gym"))
        recents.record(savedPlaylist(id = 1L, title = "Car mix"))

        assertEquals(listOf("Car mix", "Gym"), recents.getAll().map { it.title })
        // Written through DataStore, so a fresh instance sees the same history.
        assertEquals(listOf("Car mix", "Gym"), RecentPlaylistStore(store).getAll().map { it.title })
    }

    @Test
    fun coverFallsBackToTheFirstTrackArtwork() = runTest {
        val recents = RecentPlaylistStore(MemoryPreferences())
        recents.record(
            savedPlaylist(id = 3L, title = "Mix").copy(
                tracks = listOf(
                    GeneratedTrack(
                        name = "Song",
                        artist = "Artist",
                        artworkUrl = "https://example.com/art.jpg",
                    ),
                ),
            ),
        )

        assertEquals("https://example.com/art.jpg", recents.getAll().single().artworkUrl)
    }

    @Test
    fun historyIsCappedAtTwelveEntries() = runTest {
        val recents = RecentPlaylistStore(MemoryPreferences())
        repeat(15) { index ->
            recents.record(savedPlaylist(id = index.toLong(), title = "Playlist $index"))
        }

        val titles = recents.getAll().map { it.title }
        assertEquals(12, titles.size)
        assertEquals("Playlist 14", titles.first())
        assertEquals("Playlist 3", titles.last())
    }

    private fun savedPlaylist(id: Long, title: String) = SavedPlaylist(
        id = id,
        title = title,
        subtitle = "",
        mode = "manual",
        tracks = listOf(GeneratedTrack(name = "Song $id", artist = "Artist")),
        createdAtMillis = id,
    )

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
