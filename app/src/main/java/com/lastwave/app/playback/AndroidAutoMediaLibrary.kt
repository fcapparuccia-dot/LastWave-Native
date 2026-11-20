package com.lastwave.app.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.util.Base64
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.LocalAudioFormats
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.MoodGroup
import com.lastwave.app.data.music.MoodTile
import com.lastwave.app.data.music.MoodsRepository
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.podcast.toPlayableTrack
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
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
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
    private val recentPlaylistStore: RecentPlaylistStore,
    // Lazy: the chart node is the only consumer, and building the whole
    // recommendation pipeline for a widget tap or a browse root would cost
    // more than the node it feeds.
    private val generateRepository: Lazy<GenerateRepository>,
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

    private data class SearchPage(
        val query: String,
        val tracks: List<PlayableTrack>,
        val albums: List<SearchResultItem>,
        val playlists: List<SearchResultItem>,
    )

    // Charts are the one node that works on a fresh install: no playlist, no
    // download, no history. Cached so reopening the node in a car is instant.
    @Volatile private var chartTracks: List<PlayableTrack> = emptyList()
    @Volatile private var chartFetchedAtMillis = 0L
    // Moods/genres: the browse tab resolves the same page on every ignition,
    // so both the tile list and each opened genre page are kept for a while.
    @Volatile private var cachedMoodGroups: List<MoodGroup>? = null
    @Volatile private var moodGroupsFetchedAtMillis = 0L
    private val browseCategories = object : LinkedHashMap<String, List<YouTubePlaylistSummary>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<YouTubePlaylistSummary>>?,
        ): Boolean = size > MAX_CACHED_BROWSES
    }
    val playlistChanges: Flow<Unit>
        get() = merge(
            playlistRepository.changes,
            ytMusicLibraryManager.playlists.map { },
        )
    val downloadChanges get() = downloadedTrackDao.getAll()

    suspend fun loadChildren(parentId: String, state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> =
        when {
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
            parentId == LIBRARY_PODCASTS_ID -> podcastShowItems()
            parentId.startsWith(PODCAST_SHOW_PREFIX) -> podcastShowChildren(parentId)
            parentId == QUEUE_ID -> state.queue.mapIndexed(::queueTrackItem)
            parentId == CHARTS_ID -> charts().mapIndexed { index, track ->
                playableItem("$CHART_PREFIX$index", track)
            }
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
            parentId.startsWith(SEARCH_RADIO_PREFIX) -> searchRadioChildren(parentId)
            else -> emptyList()
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
        val (remote, albums, remotePlaylists) = coroutineScope {
            val tracksRequest = async { bounded(SEARCH_NETWORK_BUDGET_MS) { searchTracks(cleanQuery) }.orEmpty() }
            val albumsRequest = async {
                bounded(SEARCH_NETWORK_BUDGET_MS) { searchRepository.search(SearchTab.ALBUMS, cleanQuery) }.orEmpty()
            }
            val playlistsRequest = async {
                bounded(SEARCH_NETWORK_BUDGET_MS) { searchRepository.search(SearchTab.PLAYLISTS, cleanQuery) }.orEmpty()
            }
            Triple(tracksRequest.await(), albumsRequest.await(), playlistsRequest.await())
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
            "Car search \"$cleanQuery\": ${page.albums.size} albums, ${page.playlists.size} playlists, ${remote.size} remote tracks, ${local.size} local tracks",
        )

        return buildList {
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
        mediaId.startsWith(FAVORITE_ACTION_PREFIX) -> handleFavoriteAction(mediaId)
        mediaId.startsWith(SEARCH_RADIO_PLAY_PREFIX) -> playSearchRadio(mediaId, player)
        mediaId.startsWith(ARTIST_RADIO_PREFIX) ->
            playArtistRadio(decodeEntityId(mediaId.removePrefix(ARTIST_RADIO_PREFIX)).orEmpty(), null, player)
        mediaId == RECENT_ID -> playQueueFrom(recentlyPlayedTracks(), 0, "Recently played", player)
        mediaId == CHARTS_ID -> playQueueFrom(charts(), 0, "Popular now", player)
        mediaId.startsWith(RECENT_PREFIX) -> playQueueFrom(
            recentlyPlayedTracks(),
            mediaId.removePrefix(RECENT_PREFIX).toIntOrNull() ?: -1,
            "Recently played",
            player,
        )
        mediaId.startsWith(CHART_PREFIX) -> playQueueFrom(
            charts(),
            mediaId.removePrefix(CHART_PREFIX).toIntOrNull() ?: -1,
            "Popular now",
            player,
        )
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
                favoriteAction = false,
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
        val entityId = item.entityId?.takeIf(String::isNotBlank) ?: return emptyList()
        val isSaved = carMediaFavorites.contains(type, entityId)
        val actionId = if (isSaved) {
            favoriteRemoveId(
                type,
                entityId,
                if (type == "album") "$SEARCH_ALBUM_PREFIX$token:$index"
                else "$SEARCH_PLAYLIST_PREFIX$token:$index",
            )
        } else {
            "$FAVORITE_ACTION_PREFIX" + "save-search:$type:$token:$index"
        }
        val trackToken = cacheTracks(tracks)
        return buildList {
            add(
                actionItem(
                    mediaId = actionId,
                    title = if (isSaved) "♥ Remove from favorites" else "♡ Save to favorites",
                    subtitle = item.name,
                    favoriteAction = true,
                ),
            )
            tracks.forEachIndexed { trackIndex, track ->
                add(playableItem("$SEARCH_PREFIX$trackToken:$trackIndex", track))
            }
        }
    }

    private suspend fun savedPlaylistChildren(playlist: SavedPlaylist): List<MediaBrowserCompat.MediaItem> {
        val type = SAVED_PLAYLIST_FAVORITE_TYPE
        val entityId = playlist.id.toString()
        val isSaved = carMediaFavorites.contains(type, entityId)
        val actionId = if (isSaved) {
            favoriteRemoveId(type, entityId, "$PLAYLIST_PREFIX${playlist.id}")
        } else {
            "$FAVORITE_ACTION_PREFIX" + "save-saved-playlist:${encodeEntityId(entityId)}"
        }
        return buildList {
            add(
                actionItem(
                    mediaId = actionId,
                    title = if (isSaved) "♥ Remove from favorites" else "♡ Save to favorites",
                    subtitle = playlist.title,
                    favoriteAction = true,
                ),
            )
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
        val trackToken = cacheTracks(tracks)
        return buildList {
            add(
                actionItem(
                    mediaId = favoriteRemoveId(type, entityId, parentId),
                    title = "♥ Remove from favorites",
                    subtitle = favorite.title,
                    favoriteAction = true,
                ),
            )
            tracks.forEachIndexed { index, track ->
                add(playableItem("$SEARCH_PREFIX$trackToken:$index", track))
            }
        }
    }

    private suspend fun handleFavoriteAction(mediaId: String): Boolean {
        val action = mediaId.removePrefix(FAVORITE_ACTION_PREFIX)
        if (action.startsWith("remove:")) {
            val parts = action.removePrefix("remove:").split(':', limit = 3)
            val entityId = parts.getOrNull(1)?.let(::decodeEntityId) ?: return false
            return carMediaFavorites.remove(parts[0], entityId)
        }
        if (action.startsWith("save-saved-playlist:")) {
            val entityId = decodeEntityId(action.removePrefix("save-saved-playlist:")) ?: return false
            val playlist = entityId.toLongOrNull()?.let { resolvePlaylistWithTracks(it) } ?: return false
            carMediaFavorites.save(
                CarMediaFavorite(
                    type = SAVED_PLAYLIST_FAVORITE_TYPE,
                    entityId = entityId,
                    title = playlist.title,
                    subtitle = playlist.subtitle,
                    artworkUrl = playlist.coverUri(),
                ),
            )
            return true
        }
        if (action.startsWith("save-search:")) {
            val parts = action.removePrefix("save-search:").split(':', limit = 3)
            if (parts.size != 3) return false
            val token = parts[1]
            val index = parts[2].toIntOrNull() ?: return false
            val page = searchPage(token) ?: return false
            val item = when (parts[0]) {
                "album" -> page.albums.getOrNull(index)
                "playlist" -> page.playlists.getOrNull(index)
                else -> null
            } ?: return false
            carMediaFavorites.save(item.toCarMediaFavorite(parts[0]))
            return true
        }
        return false
    }

    private suspend fun playSearchRadio(mediaId: String, player: MusicPlayer): Boolean {
        val token = mediaId.removePrefix(SEARCH_RADIO_PLAY_PREFIX)
        val page = searchPage(token) ?: return false
        val seedTrack = page.tracks.firstOrNull { !it.videoId.isNullOrBlank() } ?: return false
        val tracks = bounded(SEARCH_DETAIL_NETWORK_BUDGET_MS) {
            artistRepository.getArtistRadio(page.query, seedTrack)
        }.orEmpty().ifEmpty { listOf(seedTrack) }
        player.playQueue(tracks, startIndex = 0, sourceLabel = "${page.query} Radio")
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
        favoriteAction: Boolean,
    ) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(
                Uri.parse(
                    "android.resource://${context.packageName}/${
                        if (favoriteAction) com.lastwave.app.R.drawable.ic_wave_widget_favorite
                        else com.lastwave.app.R.drawable.ic_car_attribution
                    }",
                ),
            )
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
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

    private fun favoriteRemoveId(type: String, entityId: String, parentId: String) =
        "$FAVORITE_ACTION_PREFIX" +
            "remove:$type:${encodeEntityId(entityId)}:${encodeEntityId(parentId)}"

    fun favoriteActionParentId(mediaId: String): String? {
        if (!mediaId.startsWith(FAVORITE_ACTION_PREFIX)) return null
        val action = mediaId.removePrefix(FAVORITE_ACTION_PREFIX)
        if (action.startsWith("remove:")) {
            val parts = action.removePrefix("remove:").split(':', limit = 3)
            return parts.getOrNull(2)?.let(::decodeEntityId)
        }
        if (action.startsWith("save-saved-playlist:")) {
            val id = decodeEntityId(action.removePrefix("save-saved-playlist:"))?.toLongOrNull() ?: return null
            return "$PLAYLIST_PREFIX$id"
        }
        if (action.startsWith("save-search:")) {
            val parts = action.removePrefix("save-search:").split(':', limit = 3)
            if (parts.size != 3) return null
            val prefix = when (parts[0]) {
                "album" -> SEARCH_ALBUM_PREFIX
                "playlist" -> SEARCH_PLAYLIST_PREFIX
                else -> return null
            }
            return "$prefix${parts[1]}:${parts[2]}"
        }
        return null
    }

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
        if (playQueueFrom(runCatching { charts() }.getOrDefault(emptyList()), 0, "Popular now", player)) return true
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

    private suspend fun browseGroupItems(): List<MediaBrowserCompat.MediaItem> =
        // Indexed on the unfiltered list: the media id carries that index, so
        // skipping an empty group here must not renumber the others.
        moodGroups().withIndex()
            .filter { (_, group) -> group.tiles.isNotEmpty() }
            .map { (index, group) ->
                browsableItem(
                    mediaId = "$BROWSE_GROUP_PREFIX$index",
                    title = group.title,
                    subtitle = if (group.tiles.size == 1) "1 genere" else "${group.tiles.size} generi",
                )
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
        if (tracks.isEmpty()) return emptyList()
        val queueToken = cacheTracks(tracks)
        return tracks.mapIndexed { trackIndex, track ->
            playableItem("$SEARCH_PREFIX$queueToken:$trackIndex", track)
        }
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
            // The app has no follow model, so this lists the artists the
            // listening history actually knows and starts their radio on tap.
            // Playable-only on purpose: a browsable flag would make every car
            // host draw a chevron into a page that has no children.
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("$ARTIST_RADIO_PREFIX${encodeEntityId(artist.name)}")
                    .setTitle(artist.name)
                    .setSubtitle("Radio dell'artista")
                    .setIconUri(artist.artworkUrl?.toSafeUri() ?: appIconUri())
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
            )
        }

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
        player.playQueue(tracks, startIndex = 0, sourceLabel = "$artist Radio")
        return true
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

    /** Account-free chart picks, cached: this is what fills an empty library. */
    private suspend fun charts(): List<PlayableTrack> {
        val cached = chartTracks
        if (cached.isNotEmpty() && System.currentTimeMillis() - chartFetchedAtMillis < CHART_CACHE_MS) {
            return cached
        }
        val fresh = bounded(CHART_NETWORK_BUDGET_MS) {
            generateRepository.get().fetchChartTracks(CHART_LIMIT).map { it.toPlayableTrack() }
        }.orEmpty()
        if (fresh.isEmpty()) return cached
        chartTracks = fresh
        chartFetchedAtMillis = System.currentTimeMillis()
        return fresh
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

    private fun playableItem(mediaId: String, track: PlayableTrack) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(track.title)
            .setSubtitle(track.artist)
            .setDescription(track.album)
            .setIconUri(track.coverUri()?.toSafeUri())
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )

    private fun appIconUri(): Uri = Uri.parse(
        "android.resource://${context.packageName}/${com.lastwave.app.R.drawable.ic_car_attribution}",
    )

    private fun trackCountLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"

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
        const val FAVORITES_ID = "category:favorites"
        const val FAVORITE_ACTION_PREFIX = "favorite-action:"
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
        private const val FAVORITE_ITEM_PREFIX = "favorite-item:"
        private const val SAVED_PLAYLIST_RESULT_PREFIX = "saved_playlist:"
        private const val SAVED_PLAYLIST_FAVORITE_TYPE = "saved_playlist"
        private const val RECENT_PREFIX = "recent:"
        private const val CHART_PREFIX = "chart:"
        // Spotify-shaped tabs: Home / Recenti / Sfoglia / Libreria.
        const val LIBRARY_ALBUMS_ID = "category:library:albums"
        private const val LIBRARY_ARTISTS_ID = "category:library:artists"
        private const val LIBRARY_PODCASTS_ID = "category:library:podcasts"
        private const val BROWSE_GROUP_PREFIX = "browse:group:"
        private const val BROWSE_TILE_PREFIX = "browse:tile:"
        private const val BROWSE_PLAYLIST_PREFIX = "browse:playlist:"
        private const val ARTIST_RADIO_PREFIX = "artist-radio:"
        private const val PODCAST_SHOW_PREFIX = "podcast-show:"
        private const val MAX_CACHED_SEARCHES = 6
        private const val MAX_CACHED_BROWSES = 6
        private const val MAX_BROWSE_PLAYLISTS = 40
        private const val MAX_BROWSE_ARTISTS = 30
        private const val MAX_BROWSE_PODCASTS = 40
        private const val MOOD_CACHE_MS = 60 * 60 * 1000L
        private const val MOOD_NETWORK_BUDGET_MS = 8_000L
        private const val MOOD_CATEGORY_BUDGET_MS = 10_000L
        private const val ARTIST_RADIO_NETWORK_BUDGET_MS = 12_000L
        private const val MAX_SEARCH_RESULTS = 30
        private const val MAX_SEARCH_CATEGORY_RESULTS = 20
        private const val RECENT_LIMIT = 50
        private const val CHART_LIMIT = 40
        private const val CHART_CACHE_MS = 30 * 60 * 1000L
        /** Android Auto drops a search that answers late. */
        private const val SEARCH_NETWORK_BUDGET_MS = 6_000L
        private const val SEARCH_DETAIL_NETWORK_BUDGET_MS = 8_000L
        private const val VOICE_SEARCH_BUDGET_MS = 10_000L
        private const val CHART_NETWORK_BUDGET_MS = 8_000L
    }
}
