package com.lastwave.app.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.util.Base64
import com.lastwave.app.data.generate.LocalAudioFormats
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.model.ArtistAlbumItem
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.MoodGroup
import com.lastwave.app.data.music.MoodTile
import com.lastwave.app.data.music.MoodsRepository
import com.lastwave.app.data.music.NewReleaseCard
import com.lastwave.app.data.music.NewReleasesCollections
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.podcast.toPlayableTrack
import com.lastwave.app.data.radio.RadioFavoritesRepository
import com.lastwave.app.data.radio.RadioRepository
import com.lastwave.app.data.radio.RadioStation
import com.lastwave.app.data.radio.toPlayableTrack as toRadioStationTrack
import com.lastwave.app.data.playlist.CarMediaFavorite
import com.lastwave.app.data.playlist.CarMediaFavorites
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.RecentPlaylistStore
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.isYouTubeOnly
import com.lastwave.app.data.repository.ArtistRepository
import com.lastwave.app.data.repository.SongPlayStatsRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import com.lastwave.app.util.ArtistHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Driver-safe browse tree shared by Android Auto and the phone player. */
@Singleton
class AndroidAutoMediaLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val ytMusicLibraryManager: YtMusicLibraryManager,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val searchRepository: SearchRepository,
    private val innerTube: InnerTubeMusicApi,
    private val carMediaFavorites: CarMediaFavorites,
    private val artistRepository: ArtistRepository,
    private val statsRepository: SongPlayStatsRepository,
    private val moodsRepository: MoodsRepository,
    private val podcastRepository: PodcastRepository,
    private val radioRepository: RadioRepository,
    private val radioFavorites: RadioFavoritesRepository,
    private val recentPlaylistStore: RecentPlaylistStore,
    private val settingsPreferences: SettingsPreferences,
) {
    private val nextSearchToken = AtomicLong()
    private val searchQueues = object : LinkedHashMap<String, List<PlayableTrack>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlayableTrack>>?): Boolean =
            size > MAX_CACHED_SEARCHES
    }
    private val searchPages = object : LinkedHashMap<String, SearchPage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchPage>?): Boolean =
            size > MAX_CACHED_SEARCHES
    }
    // Opening an artist in a car asks the same node again on every ignition and
    // every level change: the resolved page is kept per session so the songs,
    // album and playlist lists never re-hit the network.
    private val artistPages = object : LinkedHashMap<String, ArtistCarPage>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ArtistCarPage>?,
        ): Boolean = size > MAX_CACHED_ARTISTS
    }

    private data class SearchPage(
        val query: String,
        val tracks: List<PlayableTrack>,
        val albums: List<SearchResultItem>,
        val playlists: List<SearchResultItem>,
    )

    /** Everything the car search resolves in parallel: the station matches are
     *  the only hits a warm radio directory answers without waiting for a
     *  network round trip. */
    private data class SearchHits(
        val tracks: List<PlayableTrack>,
        val albums: List<SearchResultItem>,
        val playlists: List<SearchResultItem>,
        val stations: List<RadioStation>,
    )

    /** What the car shows for one artist: YouTube Music's ranked popular songs,
     *  the releases of the discography and the playlists built around them. */
    private data class ArtistCarPage(
        val name: String,
        val artworkUrl: String?,
        val topSongs: List<PlayableTrack>,
        val albums: List<ArtistAlbumItem>,
        val singles: List<ArtistAlbumItem>,
        val playlists: List<YouTubePlaylistSummary>,
    ) {
        val isEmpty: Boolean
            get() = topSongs.isEmpty() && albums.isEmpty() &&
                singles.isEmpty() && playlists.isEmpty()
    }

    /** Charts are country-scoped: the cache remembers which country it holds so
     *  switching the country can never show another chart's tracks. */
    private data class ChartCache(
        val country: ChartCountry,
        val tracks: List<PlayableTrack>,
        val fetchedAtMillis: Long,
    )

    // Charts are the one node that works on a fresh install: no playlist, no
    // download, no history. Cached so reopening the node in a car is instant.
    @Volatile private var chartCache: ChartCache? = null
    // New releases: YouTube Music's own shelves (singles, albums and release
    // playlists), cached per car session so the three sub-menus open instantly.
    @Volatile private var newReleaseShelf: NewReleasesCollections? = null
    @Volatile private var newReleasesFetchedAtMillis = 0L
    // Moods/genres: the browse tab resolves the same page on every ignition,
    // so both the tile list and each opened genre page are kept for a while.
    @Volatile private var cachedMoodGroups: List<MoodGroup>? = null
    @Volatile private var moodGroupsFetchedAtMillis = 0L
    private val browseCategories = object : LinkedHashMap<String, List<YouTubePlaylistSummary>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<YouTubePlaylistSummary>>?,
        ): Boolean = size > MAX_CACHED_BROWSES
    }

    /**
     * Rows of the last pages returned, keyed by node. Browsing a favorite row
     * (see [favoriteActionItem]) must show the page it was tapped on without
     * resolving the collection again: a network round trip puts a spinner in
     * front of the driver, and a removed favorite no longer resolves at all.
     */
    private val pageCache =
        object : LinkedHashMap<String, List<MediaBrowserCompat.MediaItem>>(MAX_CACHED_PAGES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<MediaBrowserCompat.MediaItem>>?,
            ): Boolean = size > MAX_CACHED_PAGES
        }
    val playlistChanges: Flow<Unit>
        get() = merge(
            playlistRepository.changes,
            ytMusicLibraryManager.playlists.map { },
        )
    val downloadChanges get() = downloadedTrackDao.getAll()
    /** Stations whose heart was flipped anywhere: Android Auto reloads the two nodes. */
    val radioFavoriteChanges get() = radioFavorites.favoriteIds

    /** Rows of one node. The car walks back up with its own arrow, one tap per level. */
    suspend fun loadChildren(parentId: String, state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> {
        val children = when {
            parentId == ROOT_ID -> rootItems()
            parentId == HOME_ID -> homeItems(state)
            parentId == RECENT_ID -> recentItems()
            parentId == BROWSE_ID -> browseGroupItems()
            parentId.startsWith(BROWSE_GROUP_PREFIX) -> browseTileItems(parentId)
            parentId.startsWith(BROWSE_TILE_PREFIX) -> browseCategoryItems(parentId)
            parentId.startsWith(BROWSE_PLAYLIST_PREFIX) -> browsePlaylistChildren(parentId)
            parentId == LIBRARY_ID -> libraryItems()
            parentId == LIBRARY_ALBUMS_ID -> savedAlbums().map(::favoriteItem)
            parentId == LIBRARY_ARTISTS_ID -> artistItems()
            parentId.startsWith(ARTIST_PAGE_PREFIX) -> artistPageItems(parentId.removePrefix(ARTIST_PAGE_PREFIX))
            parentId.startsWith(ARTIST_SONGS_PREFIX) -> artistSongItems(parentId.removePrefix(ARTIST_SONGS_PREFIX))
            parentId.startsWith(ARTIST_ALBUMS_PREFIX) ->
                artistReleaseItems(parentId.removePrefix(ARTIST_ALBUMS_PREFIX), singles = false)
            parentId.startsWith(ARTIST_SINGLES_PREFIX) ->
                artistReleaseItems(parentId.removePrefix(ARTIST_SINGLES_PREFIX), singles = true)
            parentId.startsWith(ARTIST_PLAYLISTS_PREFIX) ->
                artistPlaylistItems(parentId.removePrefix(ARTIST_PLAYLISTS_PREFIX))
            parentId.startsWith(ARTIST_ALBUM_PREFIX) ->
                artistReleaseChildren(parentId, ARTIST_ALBUM_PREFIX)
            parentId.startsWith(ARTIST_SINGLE_PREFIX) ->
                artistReleaseChildren(parentId, ARTIST_SINGLE_PREFIX)
            parentId.startsWith(ARTIST_PLAYLIST_PREFIX) -> artistPlaylistChildren(parentId)
            parentId == LIBRARY_PODCASTS_ID -> podcastShowItems()
            parentId.startsWith(PODCAST_SHOW_PREFIX) -> podcastShowChildren(parentId)
            parentId == RADIO_FM_ID -> radioItems()
            parentId == RADIO_FAVORITES_ID -> radioFavoriteItems()
            parentId.startsWith(RADIO_STATION_PREFIX) -> emptyList()
            parentId == QUEUE_ID -> state.queue.mapIndexed(::queueTrackItem)
            parentId == CHARTS_ID -> {
                val country = chartCountry()
                buildList {
                    // The country picker is the first row of the chart page, so
                    // the car gets YouTube Music's chart dropdown without a
                    // separate settings screen.
                    add(
                        browsableItem(
                            mediaId = CHART_COUNTRY_PICKER_ID,
                            title = "Country: ${country.label}",
                            subtitle = "Tap to change country",
                        ),
                    )
                    addAll(chartTrackItems(charts(country), country))
                }
            }
            parentId == CHART_COUNTRY_PICKER_ID -> {
                val selected = chartCountry()
                ChartCountry.entries.map { country ->
                    // Opening a country stores it, the way a dropdown selection does.
                    browsableItem(
                        mediaId = "$CHART_COUNTRY_PREFIX${country.code}",
                        title = if (country == selected) "✓ ${country.label}" else country.label,
                        subtitle = if (country == selected) "Selected" else "Tap to select",
                    )
                }
            }
            parentId.startsWith(CHART_COUNTRY_PREFIX) -> {
                val country = ChartCountry.fromCode(parentId.removePrefix(CHART_COUNTRY_PREFIX))
                    ?: chartCountry()
                selectChartCountry(country)
                chartTrackItems(charts(country), country)
            }
            parentId == NEW_RELEASES_ID -> newReleaseMenu()
            parentId.startsWith(NEW_RELEASE_GROUP_PREFIX) -> newReleaseGroupItems(parentId)
            parentId.startsWith(NEW_RELEASE_ALBUM_PREFIX) -> newReleaseChildren(parentId, isPlaylist = false)
            parentId.startsWith(NEW_RELEASE_PLAYLIST_PREFIX) -> newReleaseChildren(parentId, isPlaylist = true)
            parentId == PLAYLISTS_ID -> getMergedPlaylists().filter { it.hasPlayableTracks() }.map { playlist ->
                val trackCount = playlist.remoteTrackCount ?: playlist.tracks.size
                // Browsable-only on purpose (issue #58): flagging playlists
                // PLAYABLE makes Android Auto play from track 0 on tap
                // instead of opening the track list. Voice/assistant play
                // still works via playMediaId("$PLAYLIST_PREFIX...").
                browsableItem(
                    mediaId = "$PLAYLIST_PREFIX${playlist.id}",
                    title = playlist.title,
                    subtitle = if (playlist.isYouTubeOnly) "YouTube Music • ${trackCountLabel(trackCount)}" else trackCountLabel(trackCount),
                    iconUri = playlist.coverUri(),
                )
            }
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                val playlist = playlistId?.let { resolvePlaylistWithTracks(it) }
                if (playlist == null || playlist.tracks.isEmpty()) {
                    emptyList()
                } else {
                    savedPlaylistChildren(playlist)
                }
            }
            parentId == DOWNLOADS_ID -> downloadedTrackDao.getAllList().map(::downloadItem)
            parentId == FAVORITES_ID -> carMediaFavorites.getAll().map(::favoriteItem)
            parentId.startsWith(FAVORITE_ITEM_PREFIX) -> favoriteChildren(parentId)
            parentId.startsWith(SEARCH_ALBUMS_PREFIX) -> searchAlbumItems(parentId)
            parentId.startsWith(SEARCH_PLAYLISTS_PREFIX) -> searchPlaylistItems(parentId)
            parentId.startsWith(SEARCH_ALBUM_PREFIX) -> searchAlbumChildren(parentId)
            parentId.startsWith(SEARCH_PLAYLIST_PREFIX) -> searchPlaylistChildren(parentId)
            parentId.startsWith(SEARCH_STATIONS_PREFIX) -> searchStationItems(parentId)
            parentId.startsWith(SEARCH_RADIO_PREFIX) -> searchRadioChildren(parentId)
            parentId.startsWith(FAVORITE_ACTION_PREFIX) -> favoriteActionChildren(parentId, state)
            else -> emptyList()
        }
        // The rows a favorite action flips when it is browsed (see favoriteActionItem).
        synchronized(pageCache) { pageCache[parentId] = children }
        return children
    }

    suspend fun search(query: String): List<MediaBrowserCompat.MediaItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val matchingPlaylists = try {
            getMergedPlaylists().filter {
                it.title.contains(cleanQuery, ignoreCase = true) &&
                    (it.remoteTrackCount ?: it.tracks.size) >= 2
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
        val (remote, albums, remotePlaylists, stations) = coroutineScope {
            val tracksRequest = async { bounded(SEARCH_NETWORK_BUDGET_MS) { searchTracks(cleanQuery) }.orEmpty() }
            val albumsRequest = async {
                bounded(SEARCH_NETWORK_BUDGET_MS) { searchRepository.search(SearchTab.ALBUMS, cleanQuery) }.orEmpty()
            }
            val playlistsRequest = async {
                bounded(SEARCH_NETWORK_BUDGET_MS) { searchRepository.search(SearchTab.PLAYLISTS, cleanQuery) }.orEmpty()
            }
            // Le stazioni arrivano dalla directory già in cache: chi cerca dalla
            // lista Radio FM le vede subito, senza aspettare InnerTube.
            val stationsRequest = async { radioStationMatches(cleanQuery) }
            SearchHits(
                tracks = tracksRequest.await(),
                albums = albumsRequest.await(),
                playlists = playlistsRequest.await(),
                stations = stationsRequest.await(),
            )
        }
        val (albumsWithTracks, playlistsWithTracks) = coroutineScope {
            val albumsRequest = async {
                filterCollectionsWithMultipleTracks(
                    albums.filter { it.entityId?.isNotBlank() == true }.take(MAX_SEARCH_CATEGORY_RESULTS),
                    isPlaylist = false,
                )
            }
            val playlistsRequest = async {
                filterCollectionsWithMultipleTracks(
                    remotePlaylists.filter { it.entityId?.isNotBlank() == true }.take(MAX_SEARCH_CATEGORY_RESULTS),
                    isPlaylist = true,
                )
            }
            albumsRequest.await() to playlistsRequest.await()
        }
        val local = try {
            searchLocalTracks(cleanQuery)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
        val tracks = (remote.take(MAX_SEARCH_RESULTS * 2 / 3) + local)
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .take(MAX_SEARCH_RESULTS)
        val playlistResults = (
            playlistsWithTracks +
                matchingPlaylists.map { playlist ->
                    SearchResultItem(
                        name = playlist.title,
                        artist = if (playlist.isYouTubeOnly) "YouTube Music" else "LastWave",
                        subtitle = trackCountLabel(playlist.remoteTrackCount ?: playlist.tracks.size),
                        artworkUrl = playlist.coverUri(),
                        entityId = "saved_playlist:${playlist.id}",
                    )
                }
            )
            .distinctBy { it.entityId }
            .take(MAX_SEARCH_CATEGORY_RESULTS)
        val pageToken = nextSearchToken.incrementAndGet().toString(36)
        val page = SearchPage(
            query = cleanQuery,
            tracks = tracks,
            albums = albumsWithTracks.take(MAX_SEARCH_CATEGORY_RESULTS),
            playlists = playlistResults,
        )
        synchronized(searchPages) { searchPages[pageToken] = page }
        if (tracks.isNotEmpty()) synchronized(searchQueues) { searchQueues[pageToken] = tracks }
        android.util.Log.d(
            "AndroidAutoMediaLibrary",
            "Car search \"$cleanQuery\": ${page.albums.size} albums, ${page.playlists.size} playlists, " +
                "${stations.size} stations, ${remote.size} remote tracks, ${local.size} local tracks",
        )

        return buildList {
            // Android Auto non ha una casella di ricerca dentro una lista: la
            // lente è l'unica, e le stazioni che rispondono al testo stanno in
            // cima (anche quelle oltre le MAX_BROWSE_RADIO della lista FM).
            if (stations.isNotEmpty()) {
                add(
                    browsableItem(
                        mediaId = "$SEARCH_STATIONS_PREFIX${encodeEntityId(cleanQuery)}",
                        title = "Radio FM",
                        subtitle = countLabel(stations.size, "stazione", "stazioni"),
                    ),
                )
            }
            if (page.albums.isNotEmpty()) {
                add(browsableItem("$SEARCH_ALBUMS_PREFIX$pageToken", "Albums", "${page.albums.size} results"))
            }
            if (page.playlists.isNotEmpty()) {
                add(browsableItem("$SEARCH_PLAYLISTS_PREFIX$pageToken", "Playlists", "${page.playlists.size} results"))
            }
            if (page.tracks.any { !it.videoId.isNullOrBlank() }) {
                add(browsableItem("$SEARCH_RADIO_PREFIX$pageToken", "Radio", "Start a radio based on \"$cleanQuery\""))
            }
            page.tracks.forEachIndexed { index, track ->
                add(playableItem("$SEARCH_PREFIX$pageToken:$index", track))
            }
        }
    }

    private suspend fun filterCollectionsWithMultipleTracks(
        items: List<SearchResultItem>,
        isPlaylist: Boolean,
    ): List<SearchResultItem> = coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                val entityId = item.entityId ?: return@async null
                val tracks = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
                    if (isPlaylist) {
                        innerTube.fetchPlaylist(entityId, maxTracks = 2)?.tracks.orEmpty()
                    } else {
                        searchRepository.songsFor(item, limit = 2)
                    }
                }.orEmpty()
                item.takeIf { tracks.distinctBy(YouTubeMusicTrack::videoId).size >= 2 }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun playMediaId(mediaId: String, player: MusicPlayer): Boolean = when {
        mediaId == QUEUE_ID -> player.state.value.current?.let { player.resume(); true } ?: false
        mediaId == DOWNLOADS_ID -> playDownloads(player)
        mediaId.startsWith(SHUFFLE_ACTION_PREFIX) -> playShuffled(mediaId, player)
        mediaId.startsWith(SEARCH_RADIO_PLAY_PREFIX) -> playSearchRadio(mediaId, player)
        mediaId.startsWith(ARTIST_RADIO_PREFIX) ->
            playArtistRadio(decodeEntityId(mediaId.removePrefix(ARTIST_RADIO_PREFIX)).orEmpty(), null, player)
        mediaId.startsWith(ARTIST_TRACK_PREFIX) ->
            playCachedQueueTrack(mediaId, ARTIST_TRACK_PREFIX, "Artista", player)
        mediaId == RECENT_ID -> playQueueFrom(recentlyPlayedTracks(), 0, "Recently played", player)
        mediaId == CHARTS_ID -> playQueueFrom(charts(chartCountry()), 0, "Popular now", player)
        mediaId.startsWith(CHART_COUNTRY_PREFIX) -> playChartCountry(
            mediaId.removePrefix(CHART_COUNTRY_PREFIX),
            player,
        )
        mediaId == NEW_RELEASES_ID -> playQueueFrom(newReleaseTracks(), 0, "Nuove uscite", player)
        mediaId.startsWith(NEW_RELEASE_TRACK_PREFIX) -> playNewReleaseTrack(mediaId, player)
        mediaId.startsWith(RECENT_PREFIX) -> playQueueFrom(
            recentlyPlayedTracks(),
            mediaId.removePrefix(RECENT_PREFIX).toIntOrNull() ?: -1,
            "Recently played",
            player,
        )
        mediaId.startsWith(CHART_PREFIX) -> playChartTrack(mediaId, player)
        mediaId.startsWith(QUEUE_TRACK_PREFIX) -> {
            val index = mediaId.removePrefix(QUEUE_TRACK_PREFIX).toIntOrNull()
            if (index != null && index in player.state.value.queue.indices) {
                player.seekToQueueItem(index)
                true
            } else {
                false
            }
        }
        mediaId.startsWith(PLAYLIST_TRACK_PREFIX) -> {
            val parts = mediaId.removePrefix(PLAYLIST_TRACK_PREFIX).split(':', limit = 2)
            playPlaylist(parts.getOrNull(0)?.toLongOrNull(), parts.getOrNull(1)?.toIntOrNull(), player)
        }
        mediaId.startsWith(PLAYLIST_PREFIX) ->
            playPlaylist(mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull(), 0, player)
        mediaId.startsWith(DOWNLOAD_PREFIX) -> {
            val track = mediaId.removePrefix(DOWNLOAD_PREFIX).toLongOrNull()
                ?.let { downloadedTrackDao.findById(it) }
                ?.toPlayableTrack()
            track?.let { player.play(it, "Downloads"); true } ?: false
        }
        mediaId.startsWith(SEARCH_PREFIX) -> playCachedSearch(mediaId, player)
        mediaId.startsWith(RADIO_STATION_PREFIX) -> playRadioStation(mediaId, player)
        else -> false
    }

    private suspend fun searchAlbumItems(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val token = parentId.removePrefix(SEARCH_ALBUMS_PREFIX)
        val page = searchPage(token) ?: return emptyList()
        return page.albums.mapIndexed { index, item ->
            browsableItem(
                mediaId = "$SEARCH_ALBUM_PREFIX$token:$index",
                title = item.name,
                subtitle = item.artist.orEmpty(),
                iconUri = item.artworkUrl,
            )
        }
    }

    private suspend fun searchPlaylistItems(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val token = parentId.removePrefix(SEARCH_PLAYLISTS_PREFIX)
        val page = searchPage(token) ?: return emptyList()
        return page.playlists.mapIndexed { index, item ->
            val savedPlaylistId = item.entityId
                ?.takeIf { it.startsWith(SAVED_PLAYLIST_RESULT_PREFIX) }
                ?.removePrefix(SAVED_PLAYLIST_RESULT_PREFIX)
                ?.toLongOrNull()
            if (savedPlaylistId != null) {
                browsableItem(
                    mediaId = "$PLAYLIST_PREFIX$savedPlaylistId",
                    title = item.name,
                    subtitle = item.subtitle.orEmpty(),
                    iconUri = item.artworkUrl,
                )
            } else {
                browsableItem(
                    mediaId = "$SEARCH_PLAYLIST_PREFIX$token:$index",
                    title = item.name,
                    subtitle = item.subtitle.orEmpty(),
                    iconUri = item.artworkUrl,
                )
            }
        }
    }

    private suspend fun searchAlbumChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val (token, index) = parseSearchCollectionId(parentId, SEARCH_ALBUM_PREFIX) ?: return emptyList()
        val item = searchPage(token)?.albums?.getOrNull(index) ?: return emptyList()
        val songs = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            searchRepository.songsFor(item)
        }.orEmpty()
        val tracks = songs.map { it.toPlayableTrack(item.artist, item.name) }
        return searchResultChildren("album", token, index, item, tracks)
    }

    private suspend fun searchPlaylistChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val (token, index) = parseSearchCollectionId(parentId, SEARCH_PLAYLIST_PREFIX) ?: return emptyList()
        val item = searchPage(token)?.playlists?.getOrNull(index) ?: return emptyList()
        val playlistId = item.entityId?.takeIf(String::isNotBlank) ?: return emptyList()
        val playlist = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            innerTube.fetchPlaylist(playlistId, maxTracks = MAX_SEARCH_RESULTS)
        }
        val tracks = playlist?.tracks.orEmpty().map { it.toPlayableTrack(item.artist, item.name) }
        return searchResultChildren("playlist", token, index, item, tracks)
    }

    private suspend fun searchRadioChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val token = parentId.removePrefix(SEARCH_RADIO_PREFIX)
        val page = searchPage(token) ?: return emptyList()
        if (page.tracks.none { !it.videoId.isNullOrBlank() }) return emptyList()
        return listOf(
            actionItem(
                mediaId = "$SEARCH_RADIO_PLAY_PREFIX$token",
                title = "Start radio",
                subtitle = page.query,
            ),
        )
    }

    private suspend fun searchResultChildren(
        type: String,
        token: String,
        index: Int,
        item: SearchResultItem,
        tracks: List<PlayableTrack>,
    ): List<MediaBrowserCompat.MediaItem> {
        item.entityId?.takeIf(String::isNotBlank) ?: return emptyList()
        val parentId = if (type == "album") "$SEARCH_ALBUM_PREFIX$token:$index"
        else "$SEARCH_PLAYLIST_PREFIX$token:$index"
        return collectionRows(
            parentId = parentId,
            favorite = item.toCarMediaFavorite(type),
            label = item.name,
            tracks = tracks,
        )
    }

    private suspend fun savedPlaylistChildren(playlist: SavedPlaylist): List<MediaBrowserCompat.MediaItem> {
        val type = SAVED_PLAYLIST_FAVORITE_TYPE
        val entityId = playlist.id.toString()
        return buildList {
            add(
                favoriteActionItem(
                    type = type,
                    entityId = entityId,
                    parentId = "$PLAYLIST_PREFIX${playlist.id}",
                    collectionTitle = playlist.title,
                    isSaved = carMediaFavorites.contains(type, entityId),
                ),
            )
            add(shufflePlaylistItem(playlist))
            playlist.tracks.forEachIndexed { index, track ->
                add(
                    playableItem(
                        mediaId = "$PLAYLIST_TRACK_PREFIX${playlist.id}:$index",
                        track = track.toPlayableTrack(),
                    ),
                )
            }
        }
    }

    private suspend fun favoriteChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val (type, entityId) = decodeFavoriteItemId(parentId) ?: return emptyList()
        val favorite = carMediaFavorites.getAll().firstOrNull {
            it.type == type && it.entityId == entityId
        } ?: return emptyList()
        val tracks = when (type) {
            "album" -> bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
                searchRepository.songsFor(favorite.toSearchResultItem())
            }.orEmpty().map { it.toPlayableTrack(favorite.artist, favorite.title) }
            "playlist" -> bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
                innerTube.fetchPlaylist(entityId, maxTracks = MAX_SEARCH_RESULTS)
            }?.tracks.orEmpty().map { it.toPlayableTrack(favorite.artist, favorite.title) }
            SAVED_PLAYLIST_FAVORITE_TYPE -> entityId.toLongOrNull()
                ?.let { resolvePlaylistWithTracks(it) }
                ?.tracks.orEmpty()
                .map { it.toPlayableTrack() }
            else -> emptyList()
        }
        return collectionRows(
            parentId = parentId,
            favorite = favorite,
            label = favorite.title,
            tracks = tracks,
        )
    }

    /**
     * The page a favorite row keeps showing. Android Auto turns a tap on a
     * playable row into a play request and leaves the browse page for the now
     * playing screen; opening a browsable row instead applies the toggle and
     * returns the same rows, so the heart the driver tapped is already red.
     */
    private suspend fun favoriteActionChildren(
        mediaId: String,
        state: MusicPlayerState,
    ): List<MediaBrowserCompat.MediaItem> {
        val parentId = favoriteActionParentId(mediaId) ?: return emptyList()
        val page = synchronized(pageCache) { pageCache[parentId] } ?: loadChildren(parentId, state)
        if (!applyFavoriteAction(mediaId)) return page
        val nowSaved = favoriteActionVerb(mediaId) == "save"
        return page.map { item ->
            if (item.description.mediaId?.startsWith(FAVORITE_ACTION_PREFIX) == true) {
                flipFavoriteRow(item, nowSaved)
            } else {
                item
            }
        }
    }

    private fun flipFavoriteRow(
        item: MediaBrowserCompat.MediaItem,
        nowSaved: Boolean,
    ): MediaBrowserCompat.MediaItem {
        val description = item.description
        val mediaId = description.mediaId.orEmpty()
        val rest = mediaId.removePrefix("$FAVORITE_ACTION_PREFIX${favoriteActionVerb(mediaId)}:")
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId("$FAVORITE_ACTION_PREFIX${if (nowSaved) "remove" else "save"}:$rest")
                .setTitle(if (nowSaved) "♥ Remove from favorites" else "♡ Save to favorites")
                .setSubtitle(description.subtitle)
                .setIconUri(description.iconUri)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
        )
    }

    /** Saving and removing are idempotent, so a page the host reloads re-applies the same verb. */
    private suspend fun applyFavoriteAction(mediaId: String): Boolean {
        val verb = favoriteActionVerb(mediaId) ?: return false
        val parts = mediaId.removePrefix(FAVORITE_ACTION_PREFIX).split(':', limit = 4)
        val type = parts.getOrNull(1).orEmpty()
        val entityId = parts.getOrNull(2)?.let(::decodeEntityId) ?: return false
        if (verb == "remove") {
            carMediaFavorites.remove(type, entityId)
            return true
        }
        val parentId = parts.getOrNull(3)?.let(::decodeEntityId) ?: return false
        carMediaFavorites.save(favoriteFor(type, entityId, parentId) ?: return false)
        return true
    }

    /** The entry to save, rebuilt from the rows the action was tapped on. */
    private suspend fun favoriteFor(
        type: String,
        entityId: String,
        parentId: String,
    ): CarMediaFavorite? = when {
        type == SAVED_PLAYLIST_FAVORITE_TYPE -> entityId.toLongOrNull()
            ?.let { resolvePlaylistWithTracks(it) }
            ?.let { playlist ->
                CarMediaFavorite(
                    type = type,
                    entityId = entityId,
                    title = playlist.title,
                    subtitle = playlist.subtitle,
                    artworkUrl = playlist.coverUri(),
                )
            }
        // A collection opened from a car page resolves the same entry its
        // favorite row was built from, so saving never needs the network.
        parentId.startsWith(ARTIST_SINGLE_PREFIX) -> artistReleaseFavorite(parentId, ARTIST_SINGLE_PREFIX)
        parentId.startsWith(ARTIST_ALBUM_PREFIX) -> artistReleaseFavorite(parentId, ARTIST_ALBUM_PREFIX)
        parentId.startsWith(ARTIST_PLAYLIST_PREFIX) -> artistPlaylistFavorite(parentId)
        parentId.startsWith(BROWSE_PLAYLIST_PREFIX) -> browsePlaylistFavorite(parentId)
        parentId.startsWith(NEW_RELEASE_PLAYLIST_PREFIX) -> newReleaseFavorite(parentId, isPlaylist = true)
        parentId.startsWith(NEW_RELEASE_ALBUM_PREFIX) -> newReleaseFavorite(parentId, isPlaylist = false)
        type == "album" || type == "playlist" -> {
            val prefix = if (type == "album") SEARCH_ALBUM_PREFIX else SEARCH_PLAYLIST_PREFIX
            parseSearchCollectionId(parentId, prefix)
                ?.let { (token, index) ->
                    searchPage(token)?.let { page ->
                        if (type == "album") page.albums.getOrNull(index)
                        else page.playlists.getOrNull(index)
                    }
                }
                ?.toCarMediaFavorite(type)
        }
        else -> null
    }

    private suspend fun playSearchRadio(mediaId: String, player: MusicPlayer): Boolean {
        val token = mediaId.removePrefix(SEARCH_RADIO_PLAY_PREFIX)
        val page = searchPage(token) ?: return false
        val seedTrack = page.tracks.firstOrNull { !it.videoId.isNullOrBlank() } ?: return false
        val tracks = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            artistRepository.getArtistRadio(page.query, seedTrack)
        }.orEmpty().ifEmpty { listOf(seedTrack) }
        player.playQueue(tracks, startIndex = 0, sourceLabel = artistRadioLabel(page.query))
        return true
    }

    private fun favoriteItem(favorite: CarMediaFavorite) =
        browsableItem(
            mediaId = favoriteItemId(favorite.type, favorite.entityId),
            title = favorite.title,
            subtitle = favorite.artist?.takeIf(String::isNotBlank)
                ?: if (favorite.type == "album") "Album" else "Playlist",
            iconUri = favorite.artworkUrl,
        )

    private fun actionItem(
        mediaId: String,
        title: String,
        subtitle: String,
        iconRes: Int = com.lastwave.app.R.drawable.ic_car_attribution,
        browsable: Boolean = false,
    ) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(Uri.parse("android.resource://${context.packageName}/$iconRes"))
            .build(),
        if (browsable) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )

    private fun cacheTracks(tracks: List<PlayableTrack>): String {
        val token = nextSearchToken.incrementAndGet().toString(36)
        synchronized(searchQueues) { searchQueues[token] = tracks }
        return token
    }

    private fun searchPage(token: String): SearchPage? =
        synchronized(searchPages) { searchPages[token] }

    private fun parseSearchCollectionId(parentId: String, prefix: String): Pair<String, Int>? {
        val parts = parentId.removePrefix(prefix).split(':', limit = 2)
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return parts.firstOrNull()?.takeIf(String::isNotBlank)?.let { it to index }
    }

    private fun favoriteItemId(type: String, entityId: String) =
        "$FAVORITE_ITEM_PREFIX$type:${encodeEntityId(entityId)}"

    /**
     * A favorite row is browsable, not playable: Android Auto answers a tap on
     * a playable row with a play request and a jump to the now playing screen,
     * while a browsable row just opens the node its id names.
     */
    private fun favoriteActionItem(
        type: String,
        entityId: String,
        parentId: String,
        collectionTitle: String,
        isSaved: Boolean,
    ) = actionItem(
        mediaId = favoriteActionId(if (isSaved) "remove" else "save", type, entityId, parentId),
        title = if (isSaved) "♥ Remove from favorites" else "♡ Save to favorites",
        subtitle = collectionTitle,
        iconRes = com.lastwave.app.R.drawable.ic_wave_widget_favorite,
        browsable = true,
    )

    private fun favoriteActionId(verb: String, type: String, entityId: String, parentId: String) =
        "$FAVORITE_ACTION_PREFIX$verb:$type:${encodeEntityId(entityId)}:${encodeEntityId(parentId)}"

    private fun favoriteActionVerb(mediaId: String): String? {
        if (!mediaId.startsWith(FAVORITE_ACTION_PREFIX)) return null
        return mediaId.removePrefix(FAVORITE_ACTION_PREFIX).substringBefore(':')
            .takeIf { it == "save" || it == "remove" }
    }

    /** The page a favorite action was tapped on: the node it keeps showing. */
    fun favoriteActionParentId(mediaId: String): String? {
        if (favoriteActionVerb(mediaId) == null) return null
        val parts = mediaId.removePrefix(FAVORITE_ACTION_PREFIX).split(':', limit = 4)
        return parts.getOrNull(3)?.let(::decodeEntityId)
    }

    /** First row of a collection page: plays its tracks in random order. */
    private fun shuffleItem(label: String, trackToken: String) = actionItem(
        mediaId = "${SHUFFLE_ACTION_PREFIX}tracks:${encodeEntityId(label)}:$trackToken",
        title = "Riproduzione casuale",
        subtitle = label,
        iconRes = com.lastwave.app.R.drawable.ic_car_shuffle,
    )

    private fun shufflePlaylistItem(playlist: SavedPlaylist) = actionItem(
        mediaId = "$SHUFFLE_ACTION_PREFIX$PLAYLIST_PREFIX${playlist.id}",
        title = "Riproduzione casuale",
        subtitle = playlist.title,
        iconRes = com.lastwave.app.R.drawable.ic_car_shuffle,
    )

    private fun decodeFavoriteItemId(mediaId: String): Pair<String, String>? {
        val parts = mediaId.removePrefix(FAVORITE_ITEM_PREFIX).split(':', limit = 2)
        val type = parts.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
        val entityId = parts.getOrNull(1)?.let(::decodeEntityId) ?: return null
        return type to entityId
    }

    private fun encodeEntityId(entityId: String): String =
        Base64.encodeToString(
            entityId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun decodeEntityId(encoded: String): String? =
        try {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                .toString(Charsets.UTF_8)
                .takeIf(String::isNotBlank)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun CarMediaFavorite.toSearchResultItem() = SearchResultItem(
        name = title,
        artist = artist,
        subtitle = subtitle,
        artworkUrl = artworkUrl,
        entityId = entityId,
    )

    private fun SearchResultItem.toCarMediaFavorite(type: String) = CarMediaFavorite(
        type = type,
        entityId = requireNotNull(entityId) { "A favorite search result needs an entity ID" },
        title = name,
        subtitle = subtitle,
        artist = artist,
        artworkUrl = artworkUrl,
    )

    private fun YouTubeMusicTrack.toPlayableTrack(fallbackArtist: String?, fallbackAlbum: String?) =
        PlayableTrack(
            title = title,
            artist = artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }
                ?: fallbackArtist.orEmpty().ifBlank { "Unknown artist" },
            album = album ?: fallbackAlbum,
            artworkUrl = artworkUrl,
            videoId = videoId,
            durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
        )

    suspend fun playSearch(query: String, player: MusicPlayer): Boolean {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return false

        // Check if query directly matches a local or YouTube playlist name
        val matchingPlaylist = runCatching {
            val playlists = getMergedPlaylists()
            playlists.firstOrNull { it.title.equals(cleanQuery, ignoreCase = true) }
                ?: playlists.firstOrNull { it.title.contains(cleanQuery, ignoreCase = true) }
        }.getOrNull()

        if (matchingPlaylist != null) {
            return playPlaylist(matchingPlaylist.id, 0, player)
        }

        // Voice hits the same four 10 s InnerTube attempts as the UI, and the
        // assistant gives up long before that: bound it, then prefer an
        // offline answer to no answer at all (a car often has no usable link).
        val tracks = bounded(VOICE_SEARCH_BUDGET_MS) { searchTracks(cleanQuery) }.orEmpty()
            .ifEmpty { runCatching { searchLocalTracks(cleanQuery) }.getOrDefault(emptyList()) }
            .ifEmpty { runCatching { downloadedMatches(cleanQuery) }.getOrDefault(emptyList()) }
        if (tracks.isEmpty()) return false
        player.playQueue(tracks, sourceLabel = "Voice search: $cleanQuery")
        return true
    }

    /**
     * Voice request without a title ("play music on LastWave"): resume the
     * loaded queue, else the newest playlist that has tracks, else downloads.
     */
    suspend fun playAnything(player: MusicPlayer): Boolean {
        if (player.state.value.current != null) {
            player.resume()
            return true
        }
        val playlists = runCatching { getMergedPlaylists() }.getOrDefault(emptyList())
        for (playlist in playlists.take(5)) {
            if (runCatching { playPlaylist(playlist.id, 0, player) }.getOrDefault(false)) return true
        }
        if (runCatching { playDownloads(player) }.getOrDefault(false)) return true
        // Nothing saved and nothing downloaded: charts still start music on a
        // fresh install, so "play music on LastWave" is never a dead request.
        if (playQueueFrom(runCatching { charts(chartCountry()) }.getOrDefault(emptyList()), 0, "Popular now", player)) return true
        return playQueueFrom(
            runCatching { recentlyPlayedTracks() }.getOrDefault(emptyList()),
            0,
            "Recently played",
            player,
        )
    }

    private suspend fun getMergedPlaylists(): List<SavedPlaylist> {
        val local = runCatching { playlistRepository.getAll() }.getOrDefault(emptyList())
        val remote = runCatching { ytMusicLibraryManager.playlists.value }.getOrDefault(emptyList())
        val filteredRemote = remote.filterNot { r ->
            local.any { l ->
                (l.remotePlaylistId != null && l.remotePlaylistId.removePrefix("VL") == r.remotePlaylistId?.removePrefix("VL")) ||
                l.title.equals(r.title, ignoreCase = true)
            }
        }
        return (local + filteredRemote).distinctBy { it.id }.sortedByDescending { it.createdAtMillis }
    }

    private suspend fun resolvePlaylistWithTracks(playlistId: Long): SavedPlaylist? {
        val local = runCatching { playlistRepository.getById(playlistId) }.getOrNull()
        if (local != null && local.tracks.isNotEmpty()) {
            return local
        }
        return runCatching { ytMusicLibraryManager.loadDetail(playlistId) }.getOrNull() ?: local
    }

    /** Spotify-shaped root: four browsable tabs, nothing else. */
    private fun rootItems(): List<MediaBrowserCompat.MediaItem> = listOf(
        browsableItem(HOME_ID, "Home", "Novità, classifiche e ultimi ascolti"),
        browsableItem(RECENT_ID, "Recenti", "Brani e playlist ascoltati di recente"),
        browsableItem(BROWSE_ID, "Sfoglia", "Cerca per genere musicale"),
        browsableItem(LIBRARY_ID, "Libreria", "Playlist, album, artisti e podcast"),
    )

    private suspend fun homeItems(state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> = buildList {
        add(
            browsableItem(
                mediaId = QUEUE_ID,
                title = "In riproduzione",
                subtitle = if (state.queue.isEmpty()) "Nessuna coda attiva" else trackCountLabel(state.queue.size),
                playable = state.current != null,
            ),
        )
        // Liked Songs and Local songs are ordinary saved playlists: pointing the
        // tab straight at their media ids reuses the playlist node, its track
        // list and its voice playback instead of duplicating them here.
        runCatching { playlistRepository.getLikedSongs() }.getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() }
            ?.let { liked ->
                add(
                    browsableItem(
                        mediaId = "$PLAYLIST_PREFIX${liked.id}",
                        title = liked.title,
                        subtitle = trackCountLabel(liked.tracks.size),
                        iconUri = liked.coverUri(),
                    ),
                )
            }
        // The one node that has music on a fresh install: no playlist, no
        // download and no history yet.
        add(
            browsableItem(
                mediaId = CHARTS_ID,
                title = "Popolari ora",
                subtitle = "Di tendenza su YouTube Music",
            ),
        )
        add(
            browsableItem(
                mediaId = NEW_RELEASES_ID,
                title = "Nuove uscite",
                subtitle = "Singoli, album e playlist",
            ),
        )
        val recent = runCatching { recentlyPlayedTracks() }.getOrDefault(emptyList())
        if (recent.isNotEmpty()) {
            add(
                browsableItem(
                    mediaId = RECENT_ID,
                    title = "Ascoltati di recente",
                    subtitle = trackCountLabel(recent.size),
                ),
            )
        }
        add(
            browsableItem(
                mediaId = DOWNLOADS_ID,
                title = "Download",
                subtitle = "Musica disponibile offline",
                playable = true,
            ),
        )
        runCatching { playlistRepository.getLocalSongs() }.getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() }
            ?.let { local ->
                add(
                    browsableItem(
                        mediaId = "$PLAYLIST_PREFIX${local.id}",
                        title = "Su questo dispositivo",
                        subtitle = trackCountLabel(local.tracks.size),
                        iconUri = local.coverUri(),
                    ),
                )
            }
        val favorites = carMediaFavorites.getAll()
        if (favorites.isNotEmpty()) {
            add(
                browsableItem(
                    mediaId = FAVORITES_ID,
                    title = "Preferiti",
                    subtitle = "${favorites.size} album e playlist salvati",
                ),
            )
        }
    }

    /** Playlists first, then songs: the two things the tab promises. */
    private suspend fun recentItems(): List<MediaBrowserCompat.MediaItem> = buildList {
        runCatching { recentPlaylistStore.getAll() }.getOrDefault(emptyList()).forEach { recent ->
            add(
                browsableItem(
                    mediaId = "$PLAYLIST_PREFIX${recent.id}",
                    title = recent.title,
                    subtitle = "Playlist",
                    iconUri = recent.artworkUrl,
                ),
            )
        }
        recentlyPlayedTracks().forEachIndexed { index, track ->
            add(playableItem("$RECENT_PREFIX$index", track))
        }
    }

    private suspend fun browseGroupItems(): List<MediaBrowserCompat.MediaItem> = buildList {
        // La radio FM non è un genere: sta in cima a Sfoglia, dove il guidatore
        // la trova senza scorrere l'intero elenco di mood.
        add(browsableItem(RADIO_FM_ID, "Radio FM", "Stazioni FM italiane in diretta"))
        // Indexed on the unfiltered list: the media id carries that index, so
        // skipping an empty group here must not renumber the others.
        moodGroups().withIndex()
            .filter { (_, group) -> group.tiles.isNotEmpty() }
            .forEach { (index, group) ->
                add(
                    browsableItem(
                        mediaId = "$BROWSE_GROUP_PREFIX$index",
                        title = group.title,
                        subtitle = if (group.tiles.size == 1) "1 genere" else "${group.tiles.size} generi",
                    ),
                )
            }
    }

    private suspend fun browseTileItems(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val groupIndex = parentId.removePrefix(BROWSE_GROUP_PREFIX).toIntOrNull() ?: return emptyList()
        val group = moodGroups().getOrNull(groupIndex) ?: return emptyList()
        // Ids carry the tile's position rather than a session token: going back
        // to the genre list and reopening a genre resolves the same tile (and
        // the same cached playlist page) instead of fetching it again.
        return group.tiles.mapIndexed { tileIndex, tile ->
            browsableItem("$BROWSE_TILE_PREFIX$groupIndex:$tileIndex", tile.label, "Playlist del genere")
        }
    }

    private suspend fun browseCategoryItems(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val parts = parentId.removePrefix(BROWSE_TILE_PREFIX).split(':', limit = 2)
        val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
        val tileIndex = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val tile = moodGroups().getOrNull(groupIndex)?.tiles?.getOrNull(tileIndex) ?: return emptyList()
        val token = "$groupIndex:$tileIndex"
        return browseCategoryPlaylists(token, tile).mapIndexed { index, summary ->
            browsableItem(
                mediaId = "$BROWSE_PLAYLIST_PREFIX$token:$index",
                title = summary.title,
                subtitle = summary.author.orEmpty(),
                iconUri = summary.artworkUrl,
            )
        }
    }

    private suspend fun browsePlaylistChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        // The token is "<group>:<tile>", so the playlist index is last.
        val rest = parentId.removePrefix(BROWSE_PLAYLIST_PREFIX)
        val separator = rest.lastIndexOf(':')
        if (separator <= 0) return emptyList()
        val token = rest.substring(0, separator)
        val index = rest.substring(separator + 1).toIntOrNull() ?: return emptyList()
        val summary = synchronized(browseCategories) { browseCategories[token]?.getOrNull(index) }
            ?: return emptyList()
        val tracks = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            innerTube.fetchPlaylist(summary.id, maxTracks = MAX_SEARCH_RESULTS)
        }?.tracks.orEmpty().map { it.toPlayableTrack(summary.author, summary.title) }
        return collectionRows(
            parentId = parentId,
            favorite = summary.toCarMediaFavorite(),
            label = summary.title,
            tracks = tracks,
        )
    }

    /** The playlist a genre tile names: "browse:playlist:<group>:<tile>:<index>". */
    private suspend fun browsePlaylistFavorite(parentId: String): CarMediaFavorite? {
        val rest = parentId.removePrefix(BROWSE_PLAYLIST_PREFIX)
        val separator = rest.lastIndexOf(':')
        if (separator <= 0) return null
        val index = rest.substring(separator + 1).toIntOrNull() ?: return null
        val summary = synchronized(browseCategories) {
            browseCategories[rest.substring(0, separator)]?.getOrNull(index)
        } ?: return null
        return summary.toCarMediaFavorite()
    }

    /** Genre/mood page of the browse tab, fetched once per car session. */
    private suspend fun moodGroups(): List<MoodGroup> {
        val cached = cachedMoodGroups
        if (cached != null && System.currentTimeMillis() - moodGroupsFetchedAtMillis < MOOD_CACHE_MS) {
            return cached
        }
        val fresh = bounded(MOOD_NETWORK_BUDGET_MS) { moodsRepository.fetchGroups() }.orEmpty()
        if (fresh.isEmpty()) return cached.orEmpty()
        cachedMoodGroups = fresh
        moodGroupsFetchedAtMillis = System.currentTimeMillis()
        return fresh
    }

    private suspend fun browseCategoryPlaylists(
        token: String,
        tile: MoodTile,
    ): List<YouTubePlaylistSummary> {
        synchronized(browseCategories) { browseCategories[token] }?.let { return it }
        val playlists = bounded(MOOD_CATEGORY_BUDGET_MS) {
            moodsRepository.fetchCategory(tile.browseId, tile.params)
        }?.shelves.orEmpty()
            .flatMap { shelf -> shelf.items.filterIsInstance<HomeShelfItem.Playlist>().map { it.playlist } }
            .distinctBy { it.id }
            .take(MAX_BROWSE_PLAYLISTS)
        if (playlists.isNotEmpty()) synchronized(browseCategories) { browseCategories[token] = playlists }
        return playlists
    }

    private suspend fun libraryItems(): List<MediaBrowserCompat.MediaItem> = buildList {
        add(browsableItem(PLAYLISTS_ID, "Playlist", "Le tue playlist e i mix salvati"))
        val albums = savedAlbums()
        add(
            browsableItem(
                mediaId = LIBRARY_ALBUMS_ID,
                title = "Album salvati",
                subtitle = if (albums.isEmpty()) "Nessun album salvato" else "${albums.size} album",
            ),
        )
        val artists = topArtists()
        add(
            browsableItem(
                mediaId = LIBRARY_ARTISTS_ID,
                title = "Artisti",
                subtitle = if (artists.isEmpty()) {
                    "Ascolta qualcosa per popolare la lista"
                } else {
                    "${artists.size} artisti dai tuoi ascolti"
                },
            ),
        )
        if (runCatching { podcastRepository.isEnabledNow() }.getOrDefault(false)) {
            add(browsableItem(LIBRARY_PODCASTS_ID, "Podcast e show", "I tuoi show"))
        }
        val savedRadios = runCatching { radioFavorites.getAll() }.getOrDefault(emptyList())
        add(
            browsableItem(
                mediaId = RADIO_FAVORITES_ID,
                title = "Radio preferite",
                subtitle = if (savedRadios.isEmpty()) {
                    "Nessuna stazione salvata"
                } else {
                    countLabel(savedRadios.size, "stazione", "stazioni")
                },
            ),
        )
    }

    /** One row per Italian FM station from the directory; tap starts the stream. */
    private suspend fun radioItems(): List<MediaBrowserCompat.MediaItem> =
        runCatching { radioRepository.getItalianStations() }.getOrDefault(emptyList())
            // La browse list dell'auto arriva tutta in un colpo via IPC: mille
            // righe bloccano la UI dell'auto. La schermata telefono le mostra tutte.
            .take(MAX_BROWSE_RADIO)
            .map(::radioStationItem)

    /** Le stazioni che rispondono al testo della lente: tutta la directory,
     *  non solo le prime [MAX_BROWSE_RADIO] che la lista FM può mostrare. */
    private suspend fun radioStationMatches(query: String): List<RadioStation> =
        bounded(SEARCH_NETWORK_BUDGET_MS) { radioRepository.getItalianStations() }
            .orEmpty()
            .filter { it.matchesSearch(query) }
            .take(MAX_SEARCH_RADIO_STATIONS)

    /** La cartella aperta dalla lente: la query viaggia in base64 nell'id, così
     *  la pagina si riapre senza ricordare la ricerca precedente. */
    private suspend fun searchStationItems(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val query = decodeEntityId(parentId.removePrefix(SEARCH_STATIONS_PREFIX)) ?: return emptyList()
        return radioStationMatches(query).map(::radioStationItem)
    }

    /** La stessa riga nella lista FM e fra i risultati della lente. */
    private fun radioStationItem(station: RadioStation): MediaBrowserCompat.MediaItem {
        val track = station.toRadioStationTrack()
        val saved = station.stationUuid in radioFavorites.favoriteIds.value
        return playableItem(
            mediaId = "$RADIO_STATION_PREFIX${encodeEntityId(station.playableUrl)}",
            track = track,
            subtitle = if (saved) "♥ ${track.artist}" else track.artist,
        )
    }

    /** Le stazioni col cuore: stesso id URL della lista FM, quindi si riprendono offline. */
    private suspend fun radioFavoriteItems(): List<MediaBrowserCompat.MediaItem> =
        runCatching { radioFavorites.getAll() }.getOrDefault(emptyList()).map { favorite ->
            playableItem(
                mediaId = "$RADIO_STATION_PREFIX${encodeEntityId(favorite.streamUrl)}",
                track = favorite.toPlayableTrack(),
            )
        }

    /** Start playback of one FM station (already resolved direct stream URL). */
    private suspend fun playRadioStation(mediaId: String, player: MusicPlayer): Boolean {
        val encodedUrl = mediaId.removePrefix(RADIO_STATION_PREFIX)
        val streamUrl = decodeEntityId(encodedUrl) ?: return false
        // Le preferite portano nome, icona e MIME con sé: in auto la directory
        // può non essere raggiungibile.
        val saved = runCatching { radioFavorites.getAll() }.getOrDefault(emptyList())
            .firstOrNull { it.streamUrl.equals(streamUrl, ignoreCase = true) }
        val track = saved?.toPlayableTrack() ?: run {
            val stations = runCatching { radioRepository.getItalianStations() }.getOrDefault(emptyList())
            radioRepository.findStationByUrl(stations, streamUrl)?.toRadioStationTrack()
        } ?: return false
        player.playQueue(listOf(track), sourceLabel = "Radio FM")
        return true
    }

    private suspend fun savedAlbums(): List<CarMediaFavorite> =
        carMediaFavorites.getAll().filter { it.type == "album" }

    /** One row per artist the listening history knows, most listened first. */
    private suspend fun topArtists(limit: Int = MAX_BROWSE_ARTISTS): List<CarArtist> =
        runCatching { statsRepository.mostPlayedSince(days = 90, limit = 200) }.getOrDefault(emptyList())
            .filter { it.artist.isNotBlank() && !it.videoId.isNullOrBlank() }
            .groupBy { it.artist.trim() }
            .entries
            .sortedByDescending { entry -> entry.value.sumOf { it.totalPlayTimeMs } }
            .take(limit)
            .map { entry ->
                CarArtist(
                    name = entry.key,
                    artworkUrl = entry.value.maxByOrNull { it.lastPlayedAtMillis }?.artworkUrl,
                )
            }

    private suspend fun artistItems(): List<MediaBrowserCompat.MediaItem> =
        topArtists().map { artist ->
            // Browsable now: the tap opens the artist page (popular songs,
            // albums, singles and playlists). The radio is one row inside it,
            // so a driver can still start it with a single tap.
            browsableItem(
                mediaId = "$ARTIST_PAGE_PREFIX${encodeEntityId(artist.name)}",
                title = artist.name,
                subtitle = "Brani, album e playlist",
                iconUri = artist.artworkUrl,
            )
        }

    /**
     * One artist page. The sections mirror the phone artist screen: YouTube
     * Music's ranked popular songs first, then the releases, then the
     * playlists built around the artist. When the network gives nothing, the
     * page still offers the radio instead of opening on an empty list.
     */
    private suspend fun artistPageItems(encodedName: String): List<MediaBrowserCompat.MediaItem> {
        val requested = decodeEntityId(encodedName) ?: return emptyList()
        val page = artistPage(requested)
        val name = page?.name?.takeIf(String::isNotBlank) ?: requested
        val radio = actionItem(
            mediaId = "$ARTIST_RADIO_PREFIX${encodeEntityId(name)}",
            title = "Radio dell'artista",
            subtitle = name,
        )
        if (page == null || page.isEmpty) return listOf(radio)
        val encoded = encodeEntityId(name)
        return buildList {
            if (page.topSongs.isNotEmpty()) {
                add(
                    browsableItem(
                        mediaId = "$ARTIST_SONGS_PREFIX$encoded",
                        title = "Brani più popolari",
                        subtitle = trackCountLabel(page.topSongs.size),
                        iconUri = page.artworkUrl,
                    ),
                )
            }
            if (page.albums.isNotEmpty()) {
                add(
                    browsableItem(
                        mediaId = "$ARTIST_ALBUMS_PREFIX$encoded",
                        title = "Album",
                        subtitle = countLabel(page.albums.size, "album", "album"),
                        iconUri = page.albums.firstOrNull()?.artworkUrl ?: page.artworkUrl,
                    ),
                )
            }
            if (page.singles.isNotEmpty()) {
                add(
                    browsableItem(
                        mediaId = "$ARTIST_SINGLES_PREFIX$encoded",
                        title = "Singoli e EP",
                        subtitle = countLabel(page.singles.size, "singolo", "singoli"),
                        iconUri = page.singles.firstOrNull()?.artworkUrl ?: page.artworkUrl,
                    ),
                )
            }
            if (page.playlists.isNotEmpty()) {
                add(
                    browsableItem(
                        mediaId = "$ARTIST_PLAYLISTS_PREFIX$encoded",
                        title = "Compilation e playlist",
                        subtitle = countLabel(page.playlists.size, "playlist", "playlist"),
                        iconUri = page.playlists.firstOrNull()?.artworkUrl ?: page.artworkUrl,
                    ),
                )
            }
            add(radio)
        }
    }

    /**
     * Resolves the artist overview once per session. The page and the playlist
     * search are independent, so they run together: opening an artist costs one
     * round trip instead of two, and the full discography crawl is skipped —
     * the car only needs the shelves YouTube Music already returns.
     */
    private suspend fun artistPage(artistName: String): ArtistCarPage? {
        val name = ArtistHelper.primaryArtist(artistName).trim()
        if (name.isBlank()) return null
        val cacheKey = name.lowercase()
        synchronized(artistPages) { artistPages[cacheKey] }?.let { return it }

        val (details, playlists) = coroutineScope {
            val pageRequest = async {
                bounded(ARTIST_PAGE_NETWORK_BUDGET_MS) {
                    artistRepository.getArtistDetails(
                        name,
                        browseId = null,
                        onLoaded = {},
                        includeFullDiscography = false,
                    )
                }
            }
            val playlistRequest = async { artistPlaylistSummaries(name) }
            pageRequest.await() to playlistRequest.await()
        }
        val albums = details?.albums.orEmpty().filter { it.isCarRelease() }
        val singles = details?.singles.orEmpty()
            .filter { single -> single.isCarRelease() && albums.none { it.browseId == single.browseId } }
        val page = ArtistCarPage(
            name = details?.name?.takeIf(String::isNotBlank) ?: name,
            artworkUrl = details?.artworkUrl?.takeIf(String::isNotBlank) ?: details?.fallbackArtworkUrl,
            topSongs = details?.topSongs.orEmpty().take(MAX_ARTIST_TRACKS),
            albums = albums,
            singles = singles,
            playlists = playlists,
        )
        // Offline: leave the cache empty so the next ignition retries instead
        // of pinning an empty artist page for the whole session.
        if (details == null && page.isEmpty) return null
        synchronized(artistPages) { artistPages[cacheKey] = page }
        return page
    }

    /** Playlists YouTube Music lists around an artist: compilations, mixes and
     *  the artist's own lists. Search hits are filtered to the artist name, so
     *  a same-named song cannot drag unrelated playlists into the page. */
    private suspend fun artistPlaylistSummaries(artistName: String): List<YouTubePlaylistSummary> =
        bounded(ARTIST_DETAIL_NETWORK_BUDGET_MS) {
            innerTube.searchPlaylists(artistName, limit = MAX_ARTIST_PLAYLISTS * 2)
        }.orEmpty()
            .filter { playlist ->
                playlist.title.contains(artistName, ignoreCase = true) ||
                    playlist.author?.contains(artistName, ignoreCase = true) == true
            }
            .distinctBy { it.id }
            .take(MAX_ARTIST_PLAYLISTS)

    private suspend fun artistSongItems(encodedName: String): List<MediaBrowserCompat.MediaItem> {
        val page = decodeEntityId(encodedName)?.let { artistPage(it) } ?: return emptyList()
        return cachedArtistTrackItems(page.topSongs, page.name)
    }

    private suspend fun artistReleaseItems(
        encodedName: String,
        singles: Boolean,
    ): List<MediaBrowserCompat.MediaItem> {
        val page = decodeEntityId(encodedName)?.let { artistPage(it) } ?: return emptyList()
        val releases = if (singles) page.singles else page.albums
        val type = if (singles) "Singolo" else "Album"
        return releases.mapIndexed { index, release ->
            val prefix = if (singles) ARTIST_SINGLE_PREFIX else ARTIST_ALBUM_PREFIX
            browsableItem(
                mediaId = "$prefix$encodedName:$index",
                title = release.title,
                subtitle = listOfNotNull(type, release.year).joinToString(" • "),
                iconUri = release.artworkUrl,
            )
        }
    }

    private suspend fun artistReleaseChildren(
        parentId: String,
        prefix: String,
    ): List<MediaBrowserCompat.MediaItem> {
        val favorite = artistReleaseFavorite(parentId, prefix) ?: return emptyList()
        val tracks = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            innerTube.fetchAlbumPage(favorite.entityId, favorite.title, favorite.artist.orEmpty())?.tracks
        }.orEmpty()
        return collectionRows(
            parentId = parentId,
            favorite = favorite,
            label = favorite.title,
            tracks = tracks,
            trackMediaId = artistTrackId(favorite.title),
        )
    }

    /**
     * The album or single a release node names, typed as an album favorite so
     * saving it from the artist lands in "Album salvati" like one saved from
     * search.
     */
    private suspend fun artistReleaseFavorite(parentId: String, prefix: String): CarMediaFavorite? {
        // The token is "<base64 artist>:<release index>", so the index is last.
        val rest = parentId.removePrefix(prefix)
        val separator = rest.lastIndexOf(':')
        if (separator <= 0) return null
        val page = decodeEntityId(rest.substring(0, separator))?.let { artistPage(it) }
            ?: return null
        val index = rest.substring(separator + 1).toIntOrNull() ?: return null
        val singles = prefix == ARTIST_SINGLE_PREFIX
        val release = (if (singles) page.singles else page.albums).getOrNull(index)
            ?: return null
        if (release.browseId.isBlank()) return null
        return CarMediaFavorite(
            type = "album",
            entityId = release.browseId,
            title = release.title,
            subtitle = listOfNotNull(if (singles) "Singolo" else "Album", release.year)
                .joinToString(" • "),
            artist = page.name,
            artworkUrl = release.artworkUrl,
        )
    }

    private suspend fun artistPlaylistItems(encodedName: String): List<MediaBrowserCompat.MediaItem> {
        val page = decodeEntityId(encodedName)?.let { artistPage(it) } ?: return emptyList()
        return page.playlists.mapIndexed { index, playlist ->
            browsableItem(
                mediaId = "$ARTIST_PLAYLIST_PREFIX$encodedName:$index",
                title = playlist.title,
                subtitle = playlist.author?.takeIf(String::isNotBlank) ?: "Playlist",
                iconUri = playlist.artworkUrl,
            )
        }
    }

    private suspend fun artistPlaylistChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val favorite = artistPlaylistFavorite(parentId) ?: return emptyList()
        val tracks = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            innerTube.fetchPlaylist(favorite.entityId, maxTracks = MAX_SEARCH_RESULTS)
        }?.tracks.orEmpty().map { it.toPlayableTrack(favorite.artist, favorite.title) }
        return collectionRows(
            parentId = parentId,
            favorite = favorite,
            label = favorite.title,
            tracks = tracks,
            trackMediaId = artistTrackId(favorite.title),
        )
    }

    /** The compilation or mix a "Compilation e playlist" node names. */
    private suspend fun artistPlaylistFavorite(parentId: String): CarMediaFavorite? {
        val rest = parentId.removePrefix(ARTIST_PLAYLIST_PREFIX)
        val separator = rest.lastIndexOf(':')
        if (separator <= 0) return null
        val page = decodeEntityId(rest.substring(0, separator))?.let { artistPage(it) }
            ?: return null
        val index = rest.substring(separator + 1).toIntOrNull() ?: return null
        return page.playlists.getOrNull(index)?.toCarMediaFavorite()
    }

    private fun YouTubePlaylistSummary.toCarMediaFavorite() = CarMediaFavorite(
        type = "playlist",
        entityId = id,
        title = title,
        subtitle = author?.takeIf(String::isNotBlank) ?: trackCountText,
        artist = author,
        artworkUrl = artworkUrl,
    )

    /** Rows backed by a cached queue: the media id carries the queue label, so
     *  the car shows the artist or release name instead of a generic source. */
    private fun cachedArtistTrackItems(
        tracks: List<PlayableTrack>,
        label: String,
    ): List<MediaBrowserCompat.MediaItem> {
        if (tracks.isEmpty()) return emptyList()
        val token = cacheTracks(tracks)
        val mediaId = artistTrackId(label)
        return buildList {
            add(shuffleItem(label, token))
            tracks.forEachIndexed { index, track -> add(playableItem(mediaId(token, index), track)) }
        }
    }

    /** Rows of an artist collection name it, so the car's queue shows where it came from. */
    private fun artistTrackId(label: String): (token: String, index: Int) -> String {
        val encodedLabel = encodeEntityId(label)
        return { token, index -> "$ARTIST_TRACK_PREFIX$encodedLabel:$token:$index" }
    }

    /**
     * A collection page: the favorite row, "Riproduzione casuale" and the
     * tracks. Every page that names a whole album or playlist goes through
     * here, so the whole collection can be saved from wherever it was opened.
     */
    private suspend fun collectionRows(
        parentId: String,
        favorite: CarMediaFavorite?,
        label: String,
        tracks: List<PlayableTrack>,
        trackMediaId: (token: String, index: Int) -> String = { token, index -> "$SEARCH_PREFIX$token:$index" },
    ): List<MediaBrowserCompat.MediaItem> {
        val token = tracks.takeIf { it.isNotEmpty() }?.let(::cacheTracks)
        return buildList {
            if (favorite != null) {
                add(
                    favoriteActionItem(
                        type = favorite.type,
                        entityId = favorite.entityId,
                        parentId = parentId,
                        collectionTitle = favorite.title,
                        isSaved = carMediaFavorites.contains(favorite.type, favorite.entityId),
                    ),
                )
            }
            if (token != null) {
                add(shuffleItem(label, token))
                tracks.forEachIndexed { index, track -> add(playableItem(trackMediaId(token, index), track)) }
            }
        }
    }

    /** Artist shelves can carry playlist rows (`VL…`) among the releases. */
    private fun ArtistAlbumItem.isCarRelease(): Boolean =
        browseId.isNotBlank() && !browseId.startsWith("VL") && !browseId.startsWith("UC")

    private suspend fun podcastShowItems(): List<MediaBrowserCompat.MediaItem> {
        if (!runCatching { podcastRepository.isEnabledNow() }.getOrDefault(false)) return emptyList()
        return bounded(MOOD_NETWORK_BUDGET_MS) { podcastRepository.home() }.orEmpty()
            .flatMap { it.shows }
            .distinctBy { it.showId }
            .take(MAX_BROWSE_PODCASTS)
            .map { show ->
                browsableItem(
                    mediaId = "$PODCAST_SHOW_PREFIX${encodeEntityId(show.showId)}",
                    title = show.title,
                    subtitle = show.publisher.orEmpty(),
                    iconUri = show.artworkUrl,
                )
            }
    }

    private suspend fun podcastShowChildren(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val showId = decodeEntityId(parentId.removePrefix(PODCAST_SHOW_PREFIX)) ?: return emptyList()
        val page = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) { podcastRepository.show(showId) }
            ?: return emptyList()
        val tracks = page.episodes.map { episode -> episode.toPlayableTrack() }
        if (tracks.isEmpty()) return emptyList()
        val token = cacheTracks(tracks)
        return tracks.mapIndexed { index, track ->
            playableItem("$SEARCH_PREFIX$token:$index", track)
        }
    }

    /**
     * Starts an artist radio from [seed] (the song now playing) or from the
     * artist alone. Used by the library rows and by the car's session action,
     * so both paths publish the same queue label and fallbacks.
     */
    suspend fun playArtistRadio(artistName: String, seed: PlayableTrack?, player: MusicPlayer): Boolean {
        val artist = artistName.trim()
        if (artist.isEmpty()) return false
        val tracks = bounded(ARTIST_RADIO_NETWORK_BUDGET_MS) {
            artistRepository.getArtistRadio(artist, seed)
        }.orEmpty().ifEmpty { listOfNotNull(seed) }
        if (tracks.isEmpty()) return false
        player.playQueue(tracks, startIndex = 0, sourceLabel = artistRadioLabel(artist))
        return true
    }

    suspend fun prepareArtistRadioAfterCurrent(seed: PlayableTrack, player: MusicPlayer): Boolean {
        val tracks = bounded(ARTIST_RADIO_NETWORK_BUDGET_MS) {
            artistRepository.getArtistRadio(seed.artist, seed)
        }.orEmpty()
        return player.replaceUpcomingWithArtistRadio(seed, tracks)
    }

    private data class CarArtist(val name: String, val artworkUrl: String?)

    private fun playQueueFrom(
        tracks: List<PlayableTrack>,
        index: Int,
        label: String,
        player: MusicPlayer,
    ): Boolean {
        if (index !in tracks.indices) return false
        player.playQueue(tracks, startIndex = index, sourceLabel = label)
        return true
    }

    private suspend fun recentlyPlayedTracks(): List<PlayableTrack> =
        statsRepository.recentlyPlayed(RECENT_LIMIT)
            .mapNotNull { stats ->
                val title = stats.title.trim()
                val artist = stats.artist.trim()
                // Stats are keyed by title/artist only: a row without a video id
                // (an imported local file) has nothing here that can be resolved.
                val videoId = stats.videoId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (title.isBlank() || artist.isBlank()) return@mapNotNull null
                PlayableTrack(
                    title = title,
                    artist = artist,
                    artworkUrl = stats.artworkUrl,
                    videoId = videoId,
                )
            }
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }

    /** Chart picks of [country], cached: this is what fills an empty library. */
    private suspend fun charts(country: ChartCountry): List<PlayableTrack> {
        val cached = chartCache
        if (cached != null &&
            cached.country == country &&
            System.currentTimeMillis() - cached.fetchedAtMillis < CHART_CACHE_MS
        ) {
            return cached.tracks
        }
        val fresh = bounded(CHART_NETWORK_BUDGET_MS) {
            when (country) {
                // YouTube Music has no European chart: interleave the biggest
                // markets so "Europe" is continental instead of one country's chart.
                ChartCountry.EUROPE -> europeCharts()
                else -> innerTube.fetchCharts(country.code).map { it.toPlayableTrack() }
            }
        }?.take(CHART_LIMIT).orEmpty()
        // A rejected chart call keeps the last good list instead of standing in
        // another chart for this one: a shuffled Last.fm global chart used to be
        // served here, which made a wrong chart look like the country's.
        if (fresh.isEmpty()) return cached?.takeIf { it.country == country }?.tracks.orEmpty()
        chartCache = ChartCache(country, fresh, System.currentTimeMillis())
        return fresh
    }

    private suspend fun europeCharts(): List<PlayableTrack> = coroutineScope {
        EUROPEAN_CHART_COUNTRIES
            .map { code -> async { innerTube.fetchCharts(code).map { it.toPlayableTrack() } } }
            .awaitAll()
            .let(::interleaveCharts)
    }

    /** Round-robin merge: every market contributes its #1 before any #2. */
    private fun interleaveCharts(charts: List<List<PlayableTrack>>): List<PlayableTrack> {
        val merged = LinkedHashMap<String, PlayableTrack>()
        val longest = charts.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until longest) {
            for (tracks in charts) {
                val track = tracks.getOrNull(rank) ?: continue
                merged.putIfAbsent(
                    track.videoId ?: "${track.title.lowercase()}|${track.artist.lowercase()}",
                    track,
                )
            }
        }
        return merged.values.toList()
    }

    private fun chartTrackItems(
        tracks: List<PlayableTrack>,
        country: ChartCountry,
    ): List<MediaBrowserCompat.MediaItem> = tracks.mapIndexed { index, track ->
        playableItem("$CHART_PREFIX${country.code}:$index", track)
    }

    /** The country saved in the car, else the device's own region. */
    private suspend fun chartCountry(): ChartCountry {
        val saved = settingsPreferences.chartsCountry.first()
        return ChartCountry.fromCode(saved)
            ?: runCatching { ChartCountry.fromCode(innerTube.getEffectiveHlGl().second) }.getOrNull()
            ?: ChartCountry.WORLD
    }

    private suspend fun selectChartCountry(country: ChartCountry) {
        settingsPreferences.setChartsCountry(country.code)
    }

    /** "chart:US:7" carries its country, so a list browsed before a country
     *  switch can never play the wrong song; the legacy "chart:7" keeps the
     *  current selection. */
    private suspend fun playChartTrack(mediaId: String, player: MusicPlayer): Boolean {
        val parts = mediaId.removePrefix(CHART_PREFIX).split(':')
        val index = parts.lastOrNull()?.toIntOrNull() ?: return false
        val country = ChartCountry.fromCode(parts.getOrNull(parts.size - 2)) ?: chartCountry()
        return playQueueFrom(charts(country), index, "Popular now", player)
    }

    private suspend fun playChartCountry(code: String, player: MusicPlayer): Boolean {
        val country = ChartCountry.fromCode(code) ?: return false
        selectChartCountry(country)
        return playQueueFrom(charts(country), 0, "Popular now", player)
    }

    /** The car's "Nuove uscite" shelves, cached for the session like the charts. */
    private suspend fun newReleases(): NewReleasesCollections {
        val cached = newReleaseShelf
        if (cached != null && System.currentTimeMillis() - newReleasesFetchedAtMillis < CHART_CACHE_MS) {
            return cached
        }
        val fresh = bounded(NEW_RELEASES_NETWORK_BUDGET_MS) {
            innerTube.fetchNewReleasesCollections(limit = NEW_RELEASES_LIMIT)
        }
        if (fresh == null || fresh.isEmpty) return cached ?: NewReleasesCollections()
        newReleaseShelf = fresh
        newReleasesFetchedAtMillis = System.currentTimeMillis()
        return fresh
    }

    /** "Nuove uscite" opens on three shelves. YouTube Music has no singles page
     *  (the albums grid carries both) and no release-playlists page, so this is
     *  the only split that matches what its own "Nuove uscite" page shows. */
    private suspend fun newReleaseMenu(): List<MediaBrowserCompat.MediaItem> {
        val shelf = newReleases()
        return listOfNotNull(
            newReleaseGroupItem(NEW_RELEASE_SINGLES_GROUP, "Singoli", shelf.singles.size),
            newReleaseGroupItem(NEW_RELEASE_ALBUMS_GROUP, "Album", shelf.albums.size),
            newReleaseGroupItem(NEW_RELEASE_PLAYLISTS_GROUP, "Playlist", shelf.playlists.size),
        )
    }

    private fun newReleaseGroupItem(
        group: String,
        title: String,
        count: Int,
    ): MediaBrowserCompat.MediaItem? {
        if (count == 0) return null
        val subtitle = if (count == 1) "1 uscita" else "$count uscite"
        return browsableItem("$NEW_RELEASE_GROUP_PREFIX$group", title, subtitle)
    }

    private suspend fun newReleaseGroupItems(parentId: String): List<MediaBrowserCompat.MediaItem> {
        val shelf = newReleases()
        val cards = when (parentId.removePrefix(NEW_RELEASE_GROUP_PREFIX)) {
            NEW_RELEASE_SINGLES_GROUP -> shelf.singles
            NEW_RELEASE_ALBUMS_GROUP -> shelf.albums
            else -> shelf.playlists
        }
        return cards.map { card ->
            browsableItem(
                mediaId = if (card.isPlaylist) "$NEW_RELEASE_PLAYLIST_PREFIX${card.browseId}"
                else "$NEW_RELEASE_ALBUM_PREFIX${card.browseId}",
                title = card.title,
                subtitle = card.subtitle ?: if (card.isPlaylist) "Playlist" else "Album",
                iconUri = card.artworkUrl,
            )
        }
    }

    /** Tracks of one release. The rows carry the queue token, so a tap plays
     *  the release from that row with the release's own name as source label. */
    private suspend fun newReleaseChildren(
        parentId: String,
        isPlaylist: Boolean,
    ): List<MediaBrowserCompat.MediaItem> {
        val prefix = if (isPlaylist) NEW_RELEASE_PLAYLIST_PREFIX else NEW_RELEASE_ALBUM_PREFIX
        val browseId = parentId.removePrefix(prefix).takeIf(String::isNotBlank) ?: return emptyList()
        val card = newReleases().all.firstOrNull { it.browseId == browseId }
        val tracks = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            if (isPlaylist) {
                innerTube.fetchPlaylist(browseId, maxTracks = MAX_SEARCH_RESULTS)?.tracks
                    .orEmpty()
                    .map { it.toPlayableTrack(card?.subtitle, card?.title) }
            } else {
                innerTube.fetchAlbumPage(browseId, card?.title.orEmpty(), card?.subtitle.orEmpty())
                    ?.tracks.orEmpty()
            }
        }.orEmpty()
        if (tracks.isEmpty()) return emptyList()
        val queueLabel = card?.title ?: tracks.first().album?.takeIf(String::isNotBlank) ?: "Nuove uscite"
        val encodedLabel = encodeEntityId(queueLabel)
        return collectionRows(
            parentId = parentId,
            favorite = card?.let { newReleaseFavorite(it, isPlaylist) },
            label = queueLabel,
            tracks = tracks,
            trackMediaId = { token, index -> "$NEW_RELEASE_TRACK_PREFIX$encodedLabel:$token:$index" },
        )
    }

    /** The release a "Nuove uscite" page opens: "new-release-album:<browseId>". */
    private suspend fun newReleaseFavorite(parentId: String, isPlaylist: Boolean): CarMediaFavorite? {
        val prefix = if (isPlaylist) NEW_RELEASE_PLAYLIST_PREFIX else NEW_RELEASE_ALBUM_PREFIX
        val browseId = parentId.removePrefix(prefix).takeIf(String::isNotBlank) ?: return null
        val card = newReleases().all.firstOrNull { it.browseId == browseId } ?: return null
        return newReleaseFavorite(card, isPlaylist)
    }

    private fun newReleaseFavorite(card: NewReleaseCard, isPlaylist: Boolean) = CarMediaFavorite(
        type = if (isPlaylist) "playlist" else "album",
        entityId = card.browseId,
        title = card.title,
        subtitle = card.subtitle,
        artist = card.subtitle,
        artworkUrl = card.artworkUrl,
    )

    private fun playNewReleaseTrack(mediaId: String, player: MusicPlayer): Boolean =
        playCachedQueueTrack(mediaId, NEW_RELEASE_TRACK_PREFIX, "Nuove uscite", player)

    /**
     * Rows of every cached list (artist songs, releases, playlists, new
     * releases) carry their queue label, the token and the row index. The
     * label is what the car shows as the queue source.
     */
    private fun playCachedQueueTrack(
        mediaId: String,
        prefix: String,
        fallbackLabel: String,
        player: MusicPlayer,
    ): Boolean {
        val parts = mediaId.removePrefix(prefix).split(':', limit = 3)
        val index = parts.getOrNull(2)?.toIntOrNull() ?: return false
        val tracks = synchronized(searchQueues) { searchQueues[parts.getOrNull(1).orEmpty()] }
            ?: return false
        val label = decodeEntityId(parts.first()).orEmpty().ifBlank { fallbackLabel }
        return playQueueFrom(tracks, index, label, player)
    }

    /** Voice "play Nuove uscite": the freshest releases, flattened to a queue. */
    private suspend fun newReleaseTracks(): List<PlayableTrack> {
        val shelf = newReleases()
        val tracks = mutableListOf<PlayableTrack>()
        for (card in (shelf.singles + shelf.albums).take(NEW_RELEASE_ALBUM_BATCH)) {
            val page = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
                innerTube.fetchAlbumPage(card.browseId, card.title, card.subtitle.orEmpty())
            } ?: continue
            tracks += page.tracks
            if (tracks.size >= NEW_RELEASES_LIMIT) break
        }
        return tracks.distinctBy { it.videoId }.take(NEW_RELEASES_LIMIT)
    }

    private suspend fun playShuffled(mediaId: String, player: MusicPlayer): Boolean {
        val target = mediaId.removePrefix(SHUFFLE_ACTION_PREFIX)
        if (target.startsWith(PLAYLIST_PREFIX)) {
            val playlist = target.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                ?.let { resolvePlaylistWithTracks(it) } ?: return false
            val tracks = playlist.tracks.map { it.toPlayableTrack() }
            if (tracks.isEmpty()) return false
            player.playQueue(
                tracks = tracks,
                startIndex = 0,
                sourceLabel = playlist.title,
                startShuffled = true,
                playlistId = playlist.id,
            )
            return true
        }
        // Collections already flattened into a cached list: "tracks:<label>:<token>".
        val parts = target.removePrefix("tracks:").split(':', limit = 2)
        val tracks = synchronized(searchQueues) { searchQueues[parts.getOrNull(1).orEmpty()] }
            ?: return false
        if (tracks.isEmpty()) return false
        val label = decodeEntityId(parts.firstOrNull().orEmpty()).orEmpty().ifBlank { "Android Auto" }
        player.playQueue(tracks, startIndex = 0, sourceLabel = label, startShuffled = true)
        return true
    }

    private suspend fun playPlaylist(id: Long?, index: Int?, player: MusicPlayer): Boolean {
        val playlist = id?.let { resolvePlaylistWithTracks(it) } ?: return false
        val tracks = playlist.tracks.map { it.toPlayableTrack() }
        if (tracks.isEmpty()) return false
        player.playQueue(
            tracks = tracks,
            startIndex = index?.coerceIn(tracks.indices) ?: 0,
            sourceLabel = playlist.title,
            playlistId = playlist.id,
        )
        return true
    }

    private suspend fun playDownloads(player: MusicPlayer): Boolean {
        val tracks = downloadedTrackDao.getAllList().map { it.toPlayableTrack() }
        if (tracks.isEmpty()) return false
        player.playQueue(tracks, sourceLabel = "Downloads")
        return true
    }

    private fun playCachedSearch(mediaId: String, player: MusicPlayer): Boolean {
        val parts = mediaId.removePrefix(SEARCH_PREFIX).split(':', limit = 2)
        val token = parts.getOrNull(0) ?: return false
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val tracks = synchronized(searchQueues) { searchQueues[token] } ?: return false
        if (index !in tracks.indices) return false
        player.playQueue(tracks, startIndex = index, sourceLabel = "Android Auto Search")
        return true
    }

    private suspend fun searchTracks(query: String): List<PlayableTrack> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        return searchRepository.search(SearchTab.TRACKS, cleanQuery)
            .asSequence()
            .filter { it.name.isNotBlank() }
            .map { result ->
                PlayableTrack(
                    title = result.name,
                    artist = result.artist.orEmpty().ifBlank { "Unknown artist" },
                    album = result.subtitle,
                    artworkUrl = result.artworkUrl,
                    videoId = result.videoId,
                )
            }
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .take(MAX_SEARCH_RESULTS)
            .toList()
    }

    private suspend fun searchLocalTracks(query: String): List<PlayableTrack> =
        searchRepository.search(SearchTab.LOCAL, query)
            .asSequence()
            // Offline-playlist hits carry no file of their own.
            .filter { it.url.isNotBlank() }
            .map { result ->
                PlayableTrack(
                    title = result.name,
                    artist = result.artist.orEmpty().ifBlank { "Unknown artist" },
                    album = result.subtitle,
                    artworkUrl = result.artworkUrl,
                    playbackUrl = result.url,
                    playbackMimeType = LocalAudioFormats.mimeFor(result.url),
                    durationMs = result.durationMs,
                    trackNumber = result.trackNumber,
                )
            }
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .take(MAX_SEARCH_RESULTS)
            .toList()

    /** Last-resort voice match against downloaded files, which always play. */
    private suspend fun downloadedMatches(query: String): List<PlayableTrack> {
        val tokens = query.lowercase().split(' ').filter { it.length > 2 }
        if (tokens.isEmpty()) return emptyList()
        return downloadedTrackDao.getAllList()
            .filter { track ->
                val haystack = "${track.title} ${track.artist}".lowercase()
                tokens.all(haystack::contains)
            }
            .take(MAX_SEARCH_RESULTS)
            .map { it.toPlayableTrack() }
    }

    /**
     * A YouTube-only playlist has no local tracks at all — its count is parsed
     * from the account row and may be unknown — so it always stays visible.
     * Only local playlists with nothing in them are dropped.
     */
    private fun SavedPlaylist.hasPlayableTracks(): Boolean =
        isYouTubeOnly || tracks.isNotEmpty()

    /**
     * Runs [block] off the main thread and gives up after [timeoutMs]. Only the
     * child job is cancelled — never the caller — so a slow YouTube call cannot
     * take the browse/search response down with it.
     */
    private suspend fun <T> bounded(timeoutMs: Long, block: suspend () -> T): T? = supervisorScope {
        val job = async(Dispatchers.IO) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
        withTimeoutOrNull(timeoutMs) { job.await() } ?: run {
            job.cancel()
            null
        }
    }

    private fun queueTrackItem(index: Int, track: PlayableTrack) =
        playableItem("$QUEUE_TRACK_PREFIX$index", track)

    private fun downloadItem(track: DownloadedTrackEntity) =
        playableItem("$DOWNLOAD_PREFIX${track.id}", track.toPlayableTrack())

    private fun DownloadedTrackEntity.toPlayableTrack() = PlayableTrack(
        title = title,
        artist = artist,
        album = album.takeIf(String::isNotBlank),
        artworkUrl = artworkUrl,
        playbackUrl = mediaStoreUri?.takeIf(String::isNotBlank)
            ?: Uri.fromFile(File(filePath)).toString(),
        durationMs = durationMs.takeIf { it > 0L },
    )

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String,
        playable: Boolean = false,
        iconUri: String? = null,
    ): MediaBrowserCompat.MediaItem {
        val flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE or
            if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else 0
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(iconUri?.toSafeUri() ?: appIconUri())
                .build(),
            flags,
        )
    }

    private fun playableItem(
        mediaId: String,
        track: PlayableTrack,
        subtitle: String = track.artist,
    ) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(track.title)
            .setSubtitle(subtitle)
            .setDescription(track.album)
            .setIconUri(track.coverUri()?.toSafeUri())
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )

    private fun appIconUri(): Uri = Uri.parse(
        "android.resource://${context.packageName}/${com.lastwave.app.R.drawable.ic_car_attribution}",
    )

    private fun trackCountLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"

    private fun countLabel(count: Int, singular: String, plural: String) =
        if (count == 1) "1 $singular" else "$count $plural"

    /** Search results only carry a videoId when InnerTube matched a song —
     *  fall back to its YouTube thumbnail so Auto still shows a cover. */
    private fun PlayableTrack.coverUri(): String? {
        artworkUrl?.takeIf { it.isNotBlank() }?.let { return it }
        videoId?.takeIf { it.isNotBlank() }?.let { return "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        return null
    }

    /** Best available playlist cover: custom > remote > first track art > first track YouTube thumb. */
    private fun SavedPlaylist.coverUri(): String? {
        customCoverUri?.takeIf { it.isNotBlank() }?.let { return it }
        remoteArtworkUrl?.takeIf { it.isNotBlank() }?.let { return it }
        return tracks.firstNotNullOfOrNull { track ->
            track.artworkUrl?.takeIf { it.isNotBlank() }
                ?: track.youtubeVideoIdOrNull()?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        }
    }

    private fun String.toSafeUri(): Uri? =
        runCatching { Uri.parse(this) }.getOrNull()?.takeIf { it.toString().isNotBlank() }

    companion object {
        const val ROOT_ID = "lastwave_root"
        const val HOME_ID = "category:home"
        const val BROWSE_ID = "category:browse"
        const val LIBRARY_ID = "category:library"
        const val QUEUE_ID = "category:queue"
        const val PLAYLISTS_ID = "category:playlists"
        const val DOWNLOADS_ID = "category:downloads"
        const val RECENT_ID = "category:recent"
        const val CHARTS_ID = "category:charts"
        private const val CHART_COUNTRY_PICKER_ID = "category:charts:country"
        const val NEW_RELEASES_ID = "category:new-releases"
        const val FAVORITES_ID = "category:favorites"
        const val FAVORITE_ACTION_PREFIX = "favorite-action:"
        const val SHUFFLE_ACTION_PREFIX = "shuffle-action:"
        private const val QUEUE_TRACK_PREFIX = "queue:"
        private const val PLAYLIST_PREFIX = "playlist:"
        private const val PLAYLIST_TRACK_PREFIX = "playlist-track:"
        private const val DOWNLOAD_PREFIX = "download:"
        private const val SEARCH_PREFIX = "search:"
        private const val SEARCH_ALBUMS_PREFIX = "search:albums:"
        private const val SEARCH_PLAYLISTS_PREFIX = "search:playlists:"
        private const val SEARCH_ALBUM_PREFIX = "search:album:"
        private const val SEARCH_PLAYLIST_PREFIX = "search:playlist:"
        private const val SEARCH_RADIO_PREFIX = "search:radio:"
        private const val SEARCH_RADIO_PLAY_PREFIX = "search:radio-play:"
        /** Cartella Radio FM nei risultati della lente: prefisso + query in base64. */
        private const val SEARCH_STATIONS_PREFIX = "search:stations:"
        private const val FAVORITE_ITEM_PREFIX = "favorite-item:"
        private const val SAVED_PLAYLIST_RESULT_PREFIX = "saved_playlist:"
        private const val SAVED_PLAYLIST_FAVORITE_TYPE = "saved_playlist"
        private const val RECENT_PREFIX = "recent:"
        private const val CHART_PREFIX = "chart:"
        /** Browsable country page; the code after the colon is `ChartCountry.code`. */
        private const val CHART_COUNTRY_PREFIX = "chart-country:"
        private const val NEW_RELEASE_GROUP_PREFIX = "new-release-group:"
        private const val NEW_RELEASE_ALBUM_PREFIX = "new-release-album:"
        private const val NEW_RELEASE_PLAYLIST_PREFIX = "new-release-playlist:"
        /** Base64 release name, then the queue token, then the row index. */
        private const val NEW_RELEASE_TRACK_PREFIX = "new-release-track:"
        private const val NEW_RELEASE_SINGLES_GROUP = "singles"
        private const val NEW_RELEASE_ALBUMS_GROUP = "albums"
        private const val NEW_RELEASE_PLAYLISTS_GROUP = "playlists"
        // Spotify-shaped tabs: Home / Recenti / Sfoglia / Libreria.
        const val LIBRARY_ALBUMS_ID = "category:library:albums"
        const val LIBRARY_ARTISTS_ID = "category:library:artists"
        private const val LIBRARY_PODCASTS_ID = "category:library:podcasts"
        /** Top-level Radio FM node inside the Sfoglia tab. */
        const val RADIO_FM_ID = "category:radio_fm"
        /** FM stations saved with the heart, inside the Library tab. */
        const val RADIO_FAVORITES_ID = "category:radio_favorites"
        /** Individual playable station: prefix + base64-encoded stream URL. */
        private const val RADIO_STATION_PREFIX = "radio-station:"
        private const val BROWSE_GROUP_PREFIX = "browse:group:"
        private const val BROWSE_TILE_PREFIX = "browse:tile:"
        private const val BROWSE_PLAYLIST_PREFIX = "browse:playlist:"
        private const val ARTIST_RADIO_PREFIX = "artist-radio:"
        /** Browsable artist page: popular songs, albums, singles and playlists. */
        private const val ARTIST_PAGE_PREFIX = "artist-page:"
        private const val ARTIST_SONGS_PREFIX = "artist-songs:"
        private const val ARTIST_ALBUMS_PREFIX = "artist-albums:"
        private const val ARTIST_SINGLES_PREFIX = "artist-singles:"
        private const val ARTIST_PLAYLISTS_PREFIX = "artist-playlists:"
        /** Base64 artist name, then the release's index in its cached shelf. */
        private const val ARTIST_ALBUM_PREFIX = "artist-album:"
        private const val ARTIST_SINGLE_PREFIX = "artist-single:"
        private const val ARTIST_PLAYLIST_PREFIX = "artist-playlist:"
        /** Base64 queue label, then the queue token, then the row index. */
        private const val ARTIST_TRACK_PREFIX = "artist-track:"
        private const val PODCAST_SHOW_PREFIX = "podcast-show:"
        private const val MAX_CACHED_SEARCHES = 6
        private const val MAX_CACHED_ARTISTS = 6
        private const val MAX_ARTIST_TRACKS = 50
        private const val MAX_ARTIST_PLAYLISTS = 20
        private const val MAX_CACHED_BROWSES = 6
        /** Pages whose rows are kept for the in-place favorite toggle. */
        private const val MAX_CACHED_PAGES = 12
        private const val MAX_BROWSE_PLAYLISTS = 40
        private const val MAX_BROWSE_ARTISTS = 30
        private const val MAX_BROWSE_PODCASTS = 40
        /** Radio: la UI dell'auto non regge le ~1000 stazioni della directory IT. */
        private const val MAX_BROWSE_RADIO = 100
        /** Stazioni che la cartella Radio FM dei risultati della lente può elencare. */
        private const val MAX_SEARCH_RADIO_STATIONS = 50
        private const val MOOD_CACHE_MS = 60 * 60 * 1000L
        private const val MOOD_NETWORK_BUDGET_MS = 8_000L
        private const val MOOD_CATEGORY_BUDGET_MS = 10_000L
        private const val ARTIST_RADIO_NETWORK_BUDGET_MS = 12_000L
        /** Artist overview + release/playlist shelves; discography crawl is off. */
        private const val ARTIST_PAGE_NETWORK_BUDGET_MS = 15_000L
        private const val ARTIST_DETAIL_NETWORK_BUDGET_MS = 8_000L
        private const val MAX_SEARCH_RESULTS = 30
        private const val MAX_SEARCH_CATEGORY_RESULTS = 20
        private const val RECENT_LIMIT = 50
        private const val CHART_LIMIT = 40
        private const val CHART_CACHE_MS = 30 * 60 * 1000L
        // "Europa" has no chart of its own; the five biggest European markets
        // are interleaved into one list.
        private val EUROPEAN_CHART_COUNTRIES = listOf("IT", "GB", "DE", "FR", "ES")
        private const val NEW_RELEASES_LIMIT = 24
        private const val NEW_RELEASES_NETWORK_BUDGET_MS = 10_000L
        /** Albums opened for the voice "play Nuove uscite" queue. */
        private const val NEW_RELEASE_ALBUM_BATCH = 4
        /** Android Auto drops a search that answers late. */
        private const val SEARCH_NETWORK_BUDGET_MS = 6_000L
        private const val SEARCH_DETAIL_NETWORK_BUDGET_MS = 8_000L
        private const val VOICE_SEARCH_BUDGET_MS = 10_000L
        private const val CHART_NETWORK_BUDGET_MS = 8_000L
    }
}

/** YouTube Music's charts are country-scoped through `formData.selectedValues`
 *  (a `gl` override still returns the visitor's own chart). `ZZ` is its
 *  worldwide code and there is no European one: `EU` is rejected and silently
 *  falls back to worldwide, so "Europa" is merged from the big markets instead. */
private enum class ChartCountry(val code: String, val label: String) {
    ITALY("IT", "Italy"),
    UNITED_KINGDOM("GB", "United Kingdom"),
    UNITED_STATES("US", "United States"),
    EUROPE("EU", "Europe"),
    WORLD("ZZ", "Worldwide"),
    ;

    companion object {
        fun fromCode(code: String?): ChartCountry? {
            val normalized = code?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.code == normalized }
        }
    }
}
