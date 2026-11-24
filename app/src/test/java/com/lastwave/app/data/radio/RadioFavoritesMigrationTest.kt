package com.lastwave.app.data.radio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RadioFavoritesMigrationTest {

    private val station = RadioStation(
        stationUuid = "uuid-rai-1",
        name = "Rai Radio 1",
        url = "https://stream.example.com/rai1",
        urlResolved = "https://stream.example.com/rai1",
        tags = "news,talk",
        countryCode = "IT",
        codec = "MP3",
    )

    @Test
    fun movesStationHeartsFromLikedSongsIntoRadioFavoritesOnce() = runBlocking {
        val song = GeneratedTrack("Song", "Artist", url = "https://music.youtube.com/watch?v=abcdefghijk")
        val oldStationHeart = GeneratedTrack(name = "Rai Radio 1", artist = "news")
        // Un episodio podcast col cuore ha la stessa forma (URL vuoto) ma non è una stazione.
        val podcastHeart = GeneratedTrack(name = "Episodio 12", artist = "Un show")
        val liked = SavedPlaylist(
            id = 5L,
            title = "Liked Songs",
            subtitle = "",
            mode = LIKED_SONGS_MODE,
            tracks = listOf(song, oldStationHeart, podcastHeart),
            createdAtMillis = 1L,
        )
        val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
        coEvery { playlistRepository.getLikedSongs() } returns liked
        coEvery { playlistRepository.replaceTracksForSync(any(), any(), any()) } returns liked
        val radioRepository = mockk<RadioRepository>(relaxed = true)
        coEvery { radioRepository.getItalianStations(any()) } returns listOf(station)
        val store = MemoryPreferences()
        val favorites = RadioFavoritesRepository(store, CoroutineScope(Dispatchers.Unconfined))
        val migration = RadioFavoritesMigration(
            store,
            favorites,
            radioRepository,
            playlistRepository,
            CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(migration.migrate())

        // La stazione è in Radio preferite; brano ed episodio restano in Liked Songs.
        assertEquals(listOf("uuid-rai-1"), favorites.getAll().map { it.stationUuid })
        coVerify {
            playlistRepository.replaceTracksForSync(
                liked.id,
                listOf(song, podcastHeart),
                liked.tracks,
            )
        }

        // Una sola volta: il flag salvato ferma il secondo passaggio.
        assertTrue(migration.migrate())
        coVerify(exactly = 1) { radioRepository.getItalianStations(any()) }
    }

    @Test
    fun retriesLaterWhenTheDirectoryIsUnreachable() = runBlocking {
        val oldStationHeart = GeneratedTrack(name = "Rai Radio 1", artist = "news")
        val liked = SavedPlaylist(
            id = 1L,
            title = "Liked Songs",
            subtitle = "",
            mode = LIKED_SONGS_MODE,
            tracks = listOf(oldStationHeart),
            createdAtMillis = 1L,
        )
        val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
        coEvery { playlistRepository.getLikedSongs() } returns liked
        val radioRepository = mockk<RadioRepository>(relaxed = true)
        coEvery { radioRepository.getItalianStations(any()) } returns emptyList()
        val store = MemoryPreferences()
        val migration = RadioFavoritesMigration(
            store,
            RadioFavoritesRepository(store, CoroutineScope(Dispatchers.Unconfined)),
            radioRepository,
            playlistRepository,
            CoroutineScope(Dispatchers.Unconfined),
        )

        assertFalse(migration.migrate())
        // Nessun flag scritto: il tentativo successivo rilegge la directory.
        assertFalse(migration.migrate())
        coVerify(exactly = 2) { radioRepository.getItalianStations(any()) }
        coVerify(exactly = 0) { playlistRepository.replaceTracksForSync(any(), any(), any()) }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
