package com.lastwave.app.playback

import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.model.AlbumPageData
import com.lastwave.app.data.model.ArtistAlbumItem
import com.lastwave.app.data.model.ArtistPageData
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.MoodsRepository
import com.lastwave.app.data.music.NewReleaseCard
import com.lastwave.app.data.music.NewReleasesCollections
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.playlist.CarMediaFavorites
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.RecentPlaylistStore
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.radio.RadioFavorite
import com.lastwave.app.data.radio.RadioFavoritesRepository
import com.lastwave.app.data.radio.RadioRepository
import com.lastwave.app.data.radio.RadioStation
import com.lastwave.app.data.repository.ArtistRepository
import com.lastwave.app.data.repository.SongPlayStatsRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
            radioRepository = mockk(relaxed = true),
            radioFavorites = RadioFavoritesRepository(MemoryPreferences(), CoroutineScope(Dispatchers.Unconfined)),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
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
        // The heart is browsed, not played: a play request would leave the page.
        assertTrue(saveAction.isBrowsable)
        assertFalse(saveAction.isPlayable)
        assertEquals(
            listOf("Riproduzione casuale", "Album song", "Album song 2"),
            albumChildren.drop(1).map { it.description.title.toString() },
        )

        val toggled = library.loadChildren(saveAction.description.mediaId!!, MusicPlayerState())
        assertTrue(favorites.contains("album", "MPRE123"))
        assertEquals("♥ Remove from favorites", toggled.first().description.title.toString())
        assertEquals(
            albumChildren.drop(1).map { it.description.mediaId },
            toggled.drop(1).map { it.description.mediaId },
        )
        // Browsing the same node again re-applies the same verb instead of flipping back.
        library.loadChildren(saveAction.description.mediaId!!, MusicPlayerState())
        assertTrue(favorites.contains("album", "MPRE123"))

        val refreshedChildren = library.loadChildren(albumFolder[0].description.mediaId!!, MusicPlayerState())
        assertEquals("♥ Remove from favorites", refreshedChildren.first().description.title.toString())

        val shufflePlayer = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId(albumChildren[1].description.mediaId!!, shufflePlayer))
        verify {
            shufflePlayer.playQueue(
                match { tracks -> tracks.map { it.title } == listOf("Album song", "Album song 2") },
                startIndex = 0,
                sourceLabel = "Album",
                startShuffled = true,
            )
        }
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
    fun newReleasesOpenOnSinglesAlbumsAndPlaylistsAndPlayTheChosenRelease() = runBlocking {
        val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
        coEvery { innerTube.fetchNewReleasesCollections(any()) } returns NewReleasesCollections(
            albums = listOf(
                NewReleaseCard(browseId = "MPREb_album", title = "Full album", subtitle = "Alpha", label = "Album"),
            ),
            singles = listOf(
                NewReleaseCard(browseId = "MPREb_single", title = "Fresh single", subtitle = "Beta", label = "Singolo"),
            ),
            playlists = listOf(
                NewReleaseCard(browseId = "VLPLrelease", title = "Release playlist", subtitle = "Various", isPlaylist = true),
            ),
        )
        coEvery { innerTube.fetchAlbumPage("MPREb_single", "Fresh single", "Beta") } returns AlbumPageData(
            title = "Fresh single",
            artist = "Beta",
            browseId = "MPREb_single",
            tracks = listOf(
                PlayableTrack(title = "Single cut", artist = "Beta", videoId = "s1"),
                PlayableTrack(title = "Single b-side", artist = "Beta", videoId = "s2"),
            ),
        )
        val favorites = CarMediaFavorites(MemoryPreferences())
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = mockk(relaxed = true),
            ytMusicLibraryManager = mockk(relaxed = true),
            downloadedTrackDao = mockk(relaxed = true),
            searchRepository = mockk(relaxed = true),
            innerTube = innerTube,
            carMediaFavorites = favorites,
            artistRepository = mockk(relaxed = true),
            statsRepository = mockk(relaxed = true),
            moodsRepository = mockk(relaxed = true),
            podcastRepository = mockk(relaxed = true),
            radioRepository = mockk(relaxed = true),
            radioFavorites = RadioFavoritesRepository(MemoryPreferences(), CoroutineScope(Dispatchers.Unconfined)),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
        )

        val homeTitles = library.loadChildren(AndroidAutoMediaLibrary.HOME_ID, MusicPlayerState())
            .map { it.description.title.toString() }
        val chartsIndex = homeTitles.indexOf("Popolari ora")
        assertEquals("Nuove uscite", homeTitles[chartsIndex + 1])

        val menu = library.loadChildren(AndroidAutoMediaLibrary.NEW_RELEASES_ID, MusicPlayerState())
        assertEquals(listOf("Singoli", "Album", "Playlist"), menu.map { it.description.title.toString() })

        val singles = library.loadChildren(menu[0].description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Fresh single"), singles.map { it.description.title.toString() })

        val tracks = library.loadChildren(singles[0].description.mediaId!!, MusicPlayerState())
        assertEquals(
            listOf("♡ Save to favorites", "Riproduzione casuale", "Single cut", "Single b-side"),
            tracks.map { it.description.title.toString() },
        )

        val player = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId(tracks[3].description.mediaId!!, player))
        verify { player.playQueue(any(), startIndex = 1, sourceLabel = "Fresh single") }

        val saved = library.loadChildren(tracks[0].description.mediaId!!, MusicPlayerState())
        assertTrue(favorites.contains("album", "MPREb_single"))
        assertEquals("♥ Remove from favorites", saved.first().description.title.toString())
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
            radioRepository = mockk(relaxed = true),
            radioFavorites = RadioFavoritesRepository(MemoryPreferences(), CoroutineScope(Dispatchers.Unconfined)),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
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

    @Test
    fun artistPageOffersPopularSongsAlbumsSinglesAndPlaylists() = runBlocking {
        val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
        val statsRepository = mockk<SongPlayStatsRepository>()
        coEvery { statsRepository.mostPlayedSince(days = 90, limit = 200) } returns listOf(
            SongPlayStatsEntity(
                trackKey = "song|artist",
                title = "Song",
                artist = "Artist",
                videoId = "seed",
                totalPlayTimeMs = 10_000L,
                lastPlayedAtMillis = 5L,
            ),
        )
        val artistRepository = mockk<ArtistRepository>()
        coEvery {
            artistRepository.getArtistDetails(
                "Artist",
                browseId = null,
                onLoaded = any(),
                includeFullDiscography = false,
            )
        } returns ArtistPageData(
            name = "Artist",
            artworkUrl = "https://example.com/artist.jpg",
            topSongs = listOf(
                PlayableTrack(title = "Hit", artist = "Artist", videoId = "hit1"),
                PlayableTrack(title = "Deep cut", artist = "Artist", videoId = "hit2"),
            ),
            albums = listOf(
                ArtistAlbumItem(title = "Album One", browseId = "MPREalbum", year = "2020", type = "Album"),
            ),
            singles = listOf(
                ArtistAlbumItem(title = "Single One", browseId = "MPREsingle", year = "2021", type = "Single"),
                // Already listed under albums: the car shows each release once.
                ArtistAlbumItem(title = "Album One", browseId = "MPREalbum", year = "2020", type = "Album"),
            ),
        )
        coEvery { innerTube.searchPlaylists("Artist", 40) } returns listOf(
            YouTubePlaylistSummary(id = "VLmix", title = "Artist Greatest", author = "Curator"),
            YouTubePlaylistSummary(id = "VLother", title = "Unrelated mix", author = "Someone else"),
        )
        coEvery { innerTube.fetchAlbumPage("MPREalbum", "Album One", "Artist") } returns AlbumPageData(
            title = "Album One",
            artist = "Artist",
            browseId = "MPREalbum",
            tracks = listOf(
                PlayableTrack(title = "Album track", artist = "Artist", videoId = "a1"),
                PlayableTrack(title = "Album track 2", artist = "Artist", videoId = "a2"),
            ),
        )
        coEvery { innerTube.fetchPlaylist("VLmix", maxTracks = 30) } returns YouTubePlaylistResult(
            id = "VLmix",
            title = "Artist Greatest",
            trackCount = 1,
            tracks = listOf(YouTubeMusicTrack(videoId = "p1", title = "Mix track", artist = "Artist")),
        )
        val favorites = CarMediaFavorites(MemoryPreferences())
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = mockk(relaxed = true),
            ytMusicLibraryManager = mockk(relaxed = true),
            downloadedTrackDao = mockk(relaxed = true),
            searchRepository = mockk(relaxed = true),
            innerTube = innerTube,
            carMediaFavorites = favorites,
            artistRepository = artistRepository,
            statsRepository = statsRepository,
            moodsRepository = mockk(relaxed = true),
            podcastRepository = mockk(relaxed = true),
            radioRepository = mockk(relaxed = true),
            radioFavorites = RadioFavoritesRepository(MemoryPreferences(), CoroutineScope(Dispatchers.Unconfined)),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
        )
        val artists = library.loadChildren(AndroidAutoMediaLibrary.LIBRARY_ARTISTS_ID, MusicPlayerState())
        assertEquals(listOf("Artist"), artists.map { it.description.title.toString() })
        assertTrue(artists.single().isBrowsable)

        val page = library.loadChildren(artists.single().description.mediaId!!, MusicPlayerState())
        assertEquals(
            listOf("Brani più popolari", "Album", "Singoli e EP", "Compilation e playlist", "Radio dell'artista"),
            page.map { it.description.title.toString() },
        )

        val songs = library.loadChildren(page[0].description.mediaId!!, MusicPlayerState())
        assertEquals(
            listOf("Riproduzione casuale", "Hit", "Deep cut"),
            songs.map { it.description.title.toString() },
        )
        val player = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId(songs[2].description.mediaId!!, player))
        verify { player.playQueue(any(), startIndex = 1, sourceLabel = "Artist") }

        val albums = library.loadChildren(page[1].description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Album One"), albums.map { it.description.title.toString() })
        val albumTracks = library.loadChildren(albums.single().description.mediaId!!, MusicPlayerState())
        assertEquals(
            listOf("♡ Save to favorites", "Riproduzione casuale", "Album track", "Album track 2"),
            albumTracks.map { it.description.title.toString() },
        )
        assertTrue(library.playMediaId(albumTracks[2].description.mediaId!!, player))
        verify { player.playQueue(any(), startIndex = 0, sourceLabel = "Album One") }

        // The whole album is saved from the artist page, typed like a search hit.
        val savedAlbum = library.loadChildren(albumTracks[0].description.mediaId!!, MusicPlayerState())
        assertTrue(favorites.contains("album", "MPREalbum"))
        assertEquals("♥ Remove from favorites", savedAlbum.first().description.title.toString())

        // The album repeated in the singles shelf is not listed twice.
        val singles = library.loadChildren(page[2].description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Single One"), singles.map { it.description.title.toString() })

        val playlists = library.loadChildren(page[3].description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Artist Greatest"), playlists.map { it.description.title.toString() })
        val playlistTracks = library.loadChildren(playlists.single().description.mediaId!!, MusicPlayerState())
        assertEquals(
            listOf("♡ Save to favorites", "Riproduzione casuale", "Mix track"),
            playlistTracks.map { it.description.title.toString() },
        )
        assertTrue(library.playMediaId(playlistTracks[2].description.mediaId!!, player))
        verify { player.playQueue(any(), startIndex = 0, sourceLabel = "Artist Greatest") }

        // Same for the compilation: saved as a playlist, listed under Favorites.
        val savedPlaylist = library.loadChildren(playlistTracks[0].description.mediaId!!, MusicPlayerState())
        assertTrue(favorites.contains("playlist", "VLmix"))
        assertEquals("♥ Remove from favorites", savedPlaylist.first().description.title.toString())
        val savedAlbums = library.loadChildren(AndroidAutoMediaLibrary.LIBRARY_ALBUMS_ID, MusicPlayerState())
        assertEquals(listOf("Album One"), savedAlbums.map { it.description.title.toString() })
    }

    @Test
    fun savedPlaylistPageOffersShuffleAndTogglesTheFavoriteInPlace() = runBlocking {
        val playlist = SavedPlaylist(
            id = 7L,
            title = "Road trip",
            subtitle = "",
            mode = "",
            tracks = listOf(
                GeneratedTrack(name = "One", artist = "Artist", durationMs = 1_000L),
                GeneratedTrack(name = "Two", artist = "Artist", durationMs = 2_000L),
            ),
            createdAtMillis = 1L,
        )
        val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
        coEvery { playlistRepository.getById(7L) } returns playlist
        val favorites = CarMediaFavorites(MemoryPreferences())
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = playlistRepository,
            ytMusicLibraryManager = mockk(relaxed = true),
            downloadedTrackDao = mockk<DownloadedTrackDao>(relaxed = true),
            searchRepository = mockk(relaxed = true),
            innerTube = mockk<InnerTubeMusicApi>(relaxed = true),
            carMediaFavorites = favorites,
            artistRepository = mockk<ArtistRepository>(relaxed = true),
            statsRepository = mockk<SongPlayStatsRepository>(relaxed = true),
            moodsRepository = mockk<MoodsRepository>(relaxed = true),
            podcastRepository = mockk<PodcastRepository>(relaxed = true),
            radioRepository = mockk(relaxed = true),
            radioFavorites = RadioFavoritesRepository(MemoryPreferences(), CoroutineScope(Dispatchers.Unconfined)),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
        )

        val page = library.loadChildren("playlist:7", MusicPlayerState())
        assertEquals(
            listOf("♡ Save to favorites", "Riproduzione casuale", "One", "Two"),
            page.map { it.description.title.toString() },
        )

        val player = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId(page[1].description.mediaId!!, player))
        verify {
            player.playQueue(
                any(),
                startIndex = 0,
                sourceLabel = "Road trip",
                startShuffled = true,
                playlistId = 7L,
            )
        }

        val toggled = library.loadChildren(page[0].description.mediaId!!, MusicPlayerState())
        assertTrue(favorites.contains("saved_playlist", "7"))
        assertEquals("♥ Remove from favorites", toggled.first().description.title.toString())
        assertEquals(
            page.drop(1).map { it.description.mediaId },
            toggled.drop(1).map { it.description.mediaId },
        )
    }

    @Test
    fun radioFrequencyMovedToBrowseAndHeartedStationsSitInTheLibrary() = runBlocking {
        val station = RadioStation(
            stationUuid = "uuid-rai-1",
            name = "Rai Radio 1",
            url = "https://stream.example.com/rai1",
            urlResolved = "https://stream.example.com/rai1",
            tags = "news,talk",
            countryCode = "IT",
            codec = "MP3",
        )
        val radioRepository = mockk<RadioRepository>(relaxed = true)
        coEvery { radioRepository.getItalianStations(any()) } returns listOf(station)
        coEvery { radioRepository.findStationByUrl(any(), any()) } returns station
        val radioFavorites = RadioFavoritesRepository(
            MemoryPreferences(),
            CoroutineScope(Dispatchers.Unconfined),
        )
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
            radioRepository = radioRepository,
            radioFavorites = radioFavorites,
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
        )

        // Sfoglia apre la lista FM, Libreria non la porta più.
        val browse = library.loadChildren(AndroidAutoMediaLibrary.BROWSE_ID, MusicPlayerState())
        assertEquals("Radio FM", browse.first().description.title.toString())
        val libraryItems = library.loadChildren(AndroidAutoMediaLibrary.LIBRARY_ID, MusicPlayerState())
        val radioNode = libraryItems.first { it.description.title.toString() == "Radio preferite" }
        assertFalse(libraryItems.any { it.description.title.toString() == "Radio FM" })

        // Il nodo resta visibile anche vuoto: dice al guidatore dove finiscono i cuori.
        assertTrue(library.loadChildren(radioNode.description.mediaId!!, MusicPlayerState()).isEmpty())

        // Il cuore (lista radio o player) riempie il nodo della Libreria.
        assertTrue(radioFavorites.toggle(RadioFavorite.fromStation(station)))
        val saved = library.loadChildren(radioNode.description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Rai Radio 1"), saved.titles())

        // Una preferita parte dal suo URL salvato: nessuna lettura della directory.
        val player = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId(saved.single().description.mediaId!!, player))
        verify {
            player.playQueue(
                match { tracks -> tracks.single().radioStationUuid == "uuid-rai-1" },
                startIndex = 0,
                sourceLabel = "Radio FM",
            )
        }
        coVerify(exactly = 0) { radioRepository.getItalianStations(any()) }

        // La lista FM segna col cuore le stazioni salvate.
        val fm = library.loadChildren(AndroidAutoMediaLibrary.RADIO_FM_ID, MusicPlayerState())
        assertEquals(listOf("Rai Radio 1"), fm.titles())
        assertTrue(fm.single().description.subtitle.toString().startsWith("♥"))
    }

    @Test
    fun carSearchFindsFmStationsAndTheFolderPlaysTheStream() = runBlocking {
        val raiOne = RadioStation(
            stationUuid = "uuid-rai-1",
            name = "Rai Radio 1",
            url = "https://stream.example.com/rai1",
            urlResolved = "https://stream.example.com/rai1",
            tags = "news,talk",
            countryCode = "IT",
            codec = "MP3",
        )
        val raiTwo = RadioStation(
            stationUuid = "uuid-rai-2",
            name = "Rai Radio 2",
            url = "https://stream.example.com/rai2",
            urlResolved = "https://stream.example.com/rai2",
            tags = "music,pop",
            countryCode = "IT",
            codec = "MP3",
        )
        val deejay = RadioStation(
            stationUuid = "uuid-deejay",
            name = "Radio Deejay",
            url = "https://stream.example.com/deejay",
            urlResolved = "https://stream.example.com/deejay",
            tags = "dance",
            countryCode = "IT",
        )
        val stations = listOf(raiOne, raiTwo, deejay)
        val radioRepository = mockk<RadioRepository>(relaxed = true)
        coEvery { radioRepository.getItalianStations(any()) } returns stations
        coEvery { radioRepository.findStationByUrl(any(), any()) } answers {
            stations.firstOrNull { it.playableUrl == secondArg<String>() }
        }
        val libraryManager = mockk<YtMusicLibraryManager>(relaxed = true)
        every { libraryManager.playlists } returns MutableStateFlow(emptyList())
        val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
        coEvery { playlistRepository.getAll() } returns emptyList()
        val library = AndroidAutoMediaLibrary(
            context = mockk<Context> { every { packageName } returns "com.lastwave.app" },
            playlistRepository = playlistRepository,
            ytMusicLibraryManager = libraryManager,
            downloadedTrackDao = mockk<DownloadedTrackDao>(relaxed = true),
            searchRepository = mockk<SearchRepository>(relaxed = true),
            innerTube = mockk<InnerTubeMusicApi>(relaxed = true),
            carMediaFavorites = CarMediaFavorites(MemoryPreferences()),
            artistRepository = mockk<ArtistRepository>(relaxed = true),
            statsRepository = mockk<SongPlayStatsRepository>(relaxed = true),
            moodsRepository = mockk<MoodsRepository>(relaxed = true),
            podcastRepository = mockk<PodcastRepository>(relaxed = true),
            radioRepository = radioRepository,
            radioFavorites = RadioFavoritesRepository(
                MemoryPreferences(),
                CoroutineScope(Dispatchers.Unconfined),
            ),
            recentPlaylistStore = RecentPlaylistStore(MemoryPreferences()),
            settingsPreferences = SettingsPreferences(MemoryPreferences(), RuntimeEnvironment.getApplication()),
        )

        // La lente non può aprire una casella dentro la lista Radio FM: le
        // stazioni che rispondono al testo arrivano in cima ai risultati.
        val results = library.search("rai")
        val folder = results.single { it.description.title.toString() == "Radio FM" }
        assertEquals("2 stazioni", folder.description.subtitle.toString())
        assertTrue(folder.isBrowsable)
        assertFalse(folder.isPlayable)

        val matches = library.loadChildren(folder.description.mediaId!!, MusicPlayerState())
        assertEquals(listOf("Rai Radio 1", "Rai Radio 2"), matches.titles())
        assertTrue(matches.all { it.isPlayable })

        val player = mockk<MusicPlayer>(relaxed = true)
        assertTrue(library.playMediaId(matches.first().description.mediaId!!, player))
        verify {
            player.playQueue(
                match { tracks -> tracks.single().title == "Rai Radio 1" },
                startIndex = 0,
                sourceLabel = "Radio FM",
            )
        }

        // Un testo che non nomina nessuna stazione non aggiunge la cartella.
        assertTrue(library.search("Nomatch").none { it.description.title.toString() == "Radio FM" })
    }

    private fun List<MediaBrowserCompat.MediaItem>.titles() =
        map { it.description.title.toString() }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
