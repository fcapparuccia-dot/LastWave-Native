package com.lastwave.app.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.playlist.CarMediaFavorites
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.ArtistRepository
import com.lastwave.app.data.repository.SongPlayStatsRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidAutoMediaLibrarySearchTest {
    @Test
    fun searchShowsCategoriesBeforeSongsAndAllowsSavingAnAlbum() = runTest {
        val searchRepository = mockk<SearchRepository>()
        val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
        val libraryManager = mockk<YtMusicLibraryManager>()
        val artistRepository = mockk<ArtistRepository>(relaxed = true)
        val favorites = CarMediaFavorites(MemoryPreferences())
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = playlistRepository,
            ytMusicLibraryManager = libraryManager,
            downloadedTrackDao = mockk<DownloadedTrackDao>(relaxed = true),
            searchRepository = searchRepository,
            innerTube = mockk<InnerTubeMusicApi>(relaxed = true),
            carMediaFavorites = favorites,
            artistRepository = artistRepository,
            statsRepository = mockk<SongPlayStatsRepository>(relaxed = true),
            generateRepository = mockk<Lazy<GenerateRepository>>(relaxed = true),
        )
        val album = SearchResultItem(
            name = "Album",
            artist = "Artist",
            artworkUrl = "https://example.com/album.jpg",
            entityId = "MPRE123",
        )
        coEvery { playlistRepository.getAll() } returns emptyList()
        every { libraryManager.playlists } returns MutableStateFlow(emptyList())
        coEvery { searchRepository.search(any(), "Artist") } coAnswers {
            when (firstArg<SearchTab>()) {
                SearchTab.TRACKS -> listOf(SearchResultItem(name = "Single", artist = "Artist", videoId = "video123"))
                SearchTab.ALBUMS -> listOf(album)
                SearchTab.PLAYLISTS -> listOf(
                    SearchResultItem(name = "Mix", artist = "Artist", entityId = "PL123"),
                )
                else -> emptyList()
            }
        }
        coEvery { searchRepository.songsFor(album) } returns listOf(
            YouTubeMusicTrack(videoId = "albumSong", title = "Album song", artist = "Artist"),
        )

        val results = library.search("Artist")
        assertEquals(
            listOf("Albums", "Playlists", "Radio", "Single"),
            results.map { it.description.title.toString() },
        )
        val albumFolder = library.loadChildren(results[0].description.mediaId!!, MusicPlayerState())
        val albumChildren = library.loadChildren(albumFolder[0].description.mediaId!!, MusicPlayerState())
        val saveAction = albumChildren.first()
        assertEquals("♡ Save to favorites", saveAction.description.title.toString())

        assertTrue(library.playMediaId(saveAction.description.mediaId!!, mockk(relaxed = true)))
        assertTrue(favorites.contains("album", "MPRE123"))
        val refreshedChildren = library.loadChildren(albumFolder[0].description.mediaId!!, MusicPlayerState())
        assertEquals("♥ Remove from favorites", refreshedChildren.first().description.title.toString())
        val root = library.loadChildren(AndroidAutoMediaLibrary.ROOT_ID, MusicPlayerState())
        assertTrue(root.any { it.description.title == "Favorites" })

        val radioFolder = results[2]
        val radioItems = library.loadChildren(radioFolder.description.mediaId!!, MusicPlayerState())
        val player = mockk<MusicPlayer>(relaxed = true)
        assertEquals("Start radio", radioItems.single().description.title.toString())
        coEvery { artistRepository.getArtistRadio("Artist", any()) } returns emptyList()
        assertTrue(library.playMediaId(radioItems.single().description.mediaId!!, player))
        verify { player.playQueue(any(), startIndex = 0, sourceLabel = "Artist Radio") }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
