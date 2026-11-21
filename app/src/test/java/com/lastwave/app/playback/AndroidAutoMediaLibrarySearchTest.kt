package com.lastwave.app.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.MoodsRepository
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.newreleases.NewReleasesRepository
import com.lastwave.app.data.playlist.CarMediaFavorites
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.RecentPlaylistStore
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.repository.ArtistRepository
import com.lastwave.app.data.repository.SongPlayStatsRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidAutoMediaLibrarySearchTest {
    @Test
    fun searchShowsOnlyMultiTrackCollectionsAndAllowsSavingAnAlbum() = runBlocking {
        val searchRepository = mockk<SearchRepository>()
        val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
        val libraryManager = mockk<YtMusicLibraryManager>()
        val artistRepository = mockk<ArtistRepository>(relaxed = true)
        val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
        val favorites = CarMediaFavorites(MemoryPreferences())
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = playlistRepository,
            ytMusicLibraryManager = libraryManager,
            downloadedTrackDao = mockk<DownloadedTrackDao>(relaxed = true),
            searchRepository = searchRepository,
            innerTube = innerTube,
            carMediaFavorites = favorites,
            artistRepository = artistRepository,
            statsRepository = mockk<SongPlayStatsRepository>(relaxed = true),
            moodsRepository = mockk<MoodsRepository>(relaxed = true),
            podcastRepository = mockk<PodcastRepository>(relaxed = true),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            newReleasesRepository = mockk(relaxed = true),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
            generateRepository = mockk<Lazy<GenerateRepository>>(relaxed = true),
        )
        val album = SearchResultItem(
            name = "Album",
            artist = "Artist",
            artworkUrl = "https://example.com/album.jpg",
            entityId = "MPRE123",
        )
        val singleTrackAlbum = SearchResultItem(name = "Single-track album", entityId = "MPREsingle")
        val playlist = SearchResultItem(name = "Mix", artist = "Artist", entityId = "PL123")
        val singleTrackPlaylist = SearchResultItem(name = "Long mix", entityId = "PLlong")
        coEvery { playlistRepository.getAll() } returns listOf(
            SavedPlaylist(
                id = 1L,
                title = "Artist long mix",
                subtitle = "",
                mode = "",
                tracks = listOf(GeneratedTrack(name = "Long mix", artist = "Artist", durationMs = 7_200_000L)),
                createdAtMillis = 1L,
            ),
        )
        every { libraryManager.playlists } returns MutableStateFlow(emptyList())
        coEvery { searchRepository.search(any(), "Artist") } coAnswers {
            when (firstArg<SearchTab>()) {
                SearchTab.TRACKS -> listOf(SearchResultItem(name = "Single", artist = "Artist", videoId = "video123"))
                SearchTab.ALBUMS -> listOf(album, singleTrackAlbum)
                SearchTab.PLAYLISTS -> listOf(playlist, singleTrackPlaylist)
                else -> emptyList()
            }
        }
        coEvery { searchRepository.songsFor(any(), any()) } coAnswers {
            if (firstArg<SearchResultItem>().name == "Album") {
                listOf(
                    YouTubeMusicTrack(videoId = "albumSong", title = "Album song", artist = "Artist"),
                    YouTubeMusicTrack(videoId = "albumSong2", title = "Album song 2", artist = "Artist"),
                )
            } else {
                listOf(
                    YouTubeMusicTrack(
                        videoId = "singleAlbumSong",
                        title = "Long mix",
                        artist = "Artist",
                        durationSeconds = 7_200,
                    ),
                )
            }
        }
        coEvery { innerTube.fetchPlaylist(any(), maxTracks = any()) } coAnswers {
            val id = firstArg<String>()
            val tracks = listOf(
                YouTubeMusicTrack(
                    videoId = "playlistSong",
                    title = "Long mix",
                    artist = "Artist",
                    durationSeconds = 7_200,
                ),
            ) +
                if (id == "PL123") {
                    listOf(YouTubeMusicTrack(videoId = "playlistSong2", title = "Song 2", artist = "Artist"))
                } else {
                    emptyList()
                }
            YouTubePlaylistResult(id = id, title = "Mix", trackCount = tracks.size, tracks = tracks)
        }

        val results = library.search("Artist")
        assertEquals(
            listOf("Albums", "Playlists", "Radio", "Single"),
            results.map { it.description.title.toString() },
        )
        val albumFolder = library.loadChildren(results[0].description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Album"), albumFolder.map { it.description.title.toString() })
        val playlistFolder = library.loadChildren(results[1].description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Mix"), playlistFolder.map { it.description.title.toString() })
        val albumChildren = library.loadChildren(albumFolder[0].description.mediaId!!, MusicPlayerState())
        val saveAction = albumChildren.first()
        assertEquals("♡ Save to favorites", saveAction.description.title.toString())

        assertTrue(library.playMediaId(saveAction.description.mediaId!!, mockk(relaxed = true)))
        assertTrue(favorites.contains("album", "MPRE123"))
        val refreshedChildren = library.loadChildren(albumFolder[0].description.mediaId!!, MusicPlayerState())
        assertEquals("♥ Remove from favorites", refreshedChildren.first().description.title.toString())
        val root = library.loadChildren(AndroidAutoMediaLibrary.ROOT_ID, MusicPlayerState())
        assertEquals(
            listOf("Home", "Recenti", "Sfoglia", "Libreria"),
            root.map { it.description.title.toString() },
        )
        val homeFolder = library.loadChildren(AndroidAutoMediaLibrary.HOME_ID, MusicPlayerState())
        assertTrue(homeFolder.any { it.description.title == "Preferiti" })
        val savedAlbums = library.loadChildren(AndroidAutoMediaLibrary.LIBRARY_ALBUMS_ID, MusicPlayerState())
        assertEquals(listOf("Album"), savedAlbums.map { it.description.title.toString() })

        val radioFolder = results[2]
        val radioItems = library.loadChildren(radioFolder.description.mediaId!!, MusicPlayerState())
        val player = mockk<MusicPlayer>(relaxed = true)
        assertEquals("Start radio", radioItems.single().description.title.toString())
        coEvery { artistRepository.getArtistRadio("Artist", any()) } returns emptyList()
        assertTrue(library.playMediaId(radioItems.single().description.mediaId!!, player))
        verify { player.playQueue(any(), startIndex = 0, sourceLabel = "Artist Radio") }
    }

    @Test
    fun homeListsNewReleasesBelowChartsAndPlaysThem() = runBlocking {
        val newReleasesRepository = mockk<NewReleasesRepository>()
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = mockk(relaxed = true),
            ytMusicLibraryManager = mockk(relaxed = true),
            downloadedTrackDao = mockk(relaxed = true),
            searchRepository = mockk(relaxed = true),
            innerTube = mockk(relaxed = true),
            carMediaFavorites = CarMediaFavorites(MemoryPreferences()),
            artistRepository = mockk(relaxed = true),
            statsRepository = mockk(relaxed = true),
            moodsRepository = mockk(relaxed = true),
            podcastRepository = mockk(relaxed = true),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            newReleasesRepository = newReleasesRepository,
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
            generateRepository = mockk<Lazy<GenerateRepository>>(relaxed = true),
        )
        coEvery { newReleasesRepository.fetchPreview(any(), any()) } returns listOf(
            YouTubeMusicTrack(videoId = "new1", title = "Fresh single", artist = "New artist"),
            YouTubeMusicTrack(videoId = "new2", title = "Fresh album cut", artist = "New artist"),
        )

        val homeTitles = library.loadChildren(AndroidAutoMediaLibrary.HOME_ID, MusicPlayerState())
            .map { it.description.title.toString() }
        val chartsIndex = homeTitles.indexOf("Popolari ora")
        assertEquals("Nuove uscite", homeTitles[chartsIndex + 1])

        val tracks = library.loadChildren(AndroidAutoMediaLibrary.NEW_RELEASES_ID, MusicPlayerState())
        assertEquals(listOf("Fresh single", "Fresh album cut"), tracks.map { it.description.title.toString() })

        val player = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId(tracks[1].description.mediaId!!, player))
        verify { player.playQueue(any(), startIndex = 1, sourceLabel = "New releases") }
    }

    @Test
    fun chartsPageOffersTheCountryPickerAndPlaysTheSelectedChart() = runBlocking {
        val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
        every { innerTube.getEffectiveHlGl() } returns ("it" to "IT")
        coEvery { innerTube.fetchCharts("IT") } returns listOf(
            YouTubeMusicTrack(videoId = "it1", title = "Italiana", artist = "Geolier"),
        )
        coEvery { innerTube.fetchCharts("US") } returns listOf(
            YouTubeMusicTrack(videoId = "us1", title = "Americana", artist = "Drake"),
        )
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = mockk(relaxed = true),
            ytMusicLibraryManager = mockk(relaxed = true),
            downloadedTrackDao = mockk(relaxed = true),
            searchRepository = mockk(relaxed = true),
            innerTube = innerTube,
            carMediaFavorites = CarMediaFavorites(MemoryPreferences()),
            artistRepository = mockk(relaxed = true),
            statsRepository = mockk(relaxed = true),
            moodsRepository = mockk(relaxed = true),
            podcastRepository = mockk(relaxed = true),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            newReleasesRepository = mockk(relaxed = true),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
            generateRepository = mockk<Lazy<GenerateRepository>>(relaxed = true),
        )

        // The device region is the default selection, and it is the first row.
        val chartPage = library.loadChildren(AndroidAutoMediaLibrary.CHARTS_ID, MusicPlayerState())
        assertEquals("Country: Italy", chartPage.first().description.title.toString())
        assertEquals(listOf("chart:IT:0"), chartPage.drop(1).map { it.description.mediaId })

        val countries = library.loadChildren(chartPage.first().description.mediaId!!, MusicPlayerState())
        assertEquals(
            listOf("✓ Italy", "United Kingdom", "United States", "Europe", "Worldwide"),
            countries.map { it.description.title.toString() },
        )
        assertEquals(
            listOf("chart-country:IT", "chart-country:GB", "chart-country:US", "chart-country:EU", "chart-country:ZZ"),
            countries.map { it.description.mediaId },
        )

        val usPage = library.loadChildren(countries[2].description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("chart:US:0"), usPage.map { it.description.mediaId })

        // Opening a country is the selection: the chart page now shows the US.
        val refreshed = library.loadChildren(AndroidAutoMediaLibrary.CHARTS_ID, MusicPlayerState())
        assertEquals("Country: United States", refreshed.first().description.title.toString())

        // "chart:IT:0" still plays Italy even though the picker now says US.
        val player = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId("chart:IT:0", player))
        verify {
            player.playQueue(
                match { tracks -> tracks.map { it.title } == listOf("Italiana") },
                startIndex = 0,
                sourceLabel = "Popular now",
            )
        }

        // Tapping a country row plays that country and remembers it.
        assertTrue(library.playMediaId("chart-country:US", player))
        verify {
            player.playQueue(
                match { tracks -> tracks.map { it.title } == listOf("Americana") },
                startIndex = 0,
                sourceLabel = "Popular now",
            )
        }
        assertEquals("Country: United States", library.loadChildren(AndroidAutoMediaLibrary.CHARTS_ID, MusicPlayerState()).first().description.title.toString())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
