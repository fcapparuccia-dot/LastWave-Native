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
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.playlist.CarMediaFavorite
import com.lastwave.app.data.playlist.CarMediaFavorites
import com.lastwave.app.data.playlist.PlaylistRepository
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
    val playlistChanges: Flow<Unit>
        get() = merge(
            playlistRepository.changes,
            ytMusicLibraryManager.playlists.map { },
        )
    val downloadChanges get() = downloadedTrackDao.getAll()

    suspend fun loadChildren(parentId: String, state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> =
        when {
            parentId == ROOT_ID -> rootItems(state)
            parentId == QUEUE_ID -> state.queue.mapIndexed(::queueTrackItem)
            parentId == RECENT_ID -> recentlyPlayedTracks().mapIndexed { index, track ->
                playableItem("$RECENT_PREFIX$index", track)
            }
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
            getMergedPlaylists().filter { it.title.contains(cleanQuery, ignoreCase = true) }
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
            remotePlaylists.filter { it.entityId?.isNotBlank() == true } +
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
            albums = albums.filter { it.entityId?.isNotBlank() == true }.take(MAX_SEARCH_CATEGORY_RESULTS),
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

    suspend fun playMediaId(mediaId: String, player: MusicPlayer): Boolean = when {
        mediaId == QUEUE_ID -> player.state.value.current?.let { player.resume(); true } ?: false
        mediaId == DOWNLOADS_ID -> playDownloads(player)
        mediaId.startsWith(FAVORITE_ACTION_PREFIX) -> handleFavoriteAction(mediaId)
        mediaId.startsWith(SEARCH_RADIO_PLAY_PREFIX) -> playSearchRadio(mediaId, player)
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

    private suspend fun rootItems(state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> = buildList {
        add(
            browsableItem(
                mediaId = QUEUE_ID,
                title = "Up next",
                subtitle = if (state.queue.isEmpty()) "No active queue" else trackCountLabel(state.queue.size),
                playable = state.current != null,
            ),
        )
        // Liked Songs and Local songs are ordinary saved playlists: pointing the
        // root straight at their media ids reuses the playlist node, its track
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
                title = "Popular now",
                subtitle = "Trending on YouTube Music",
            ),
        )
        val recent = runCatching { recentlyPlayedTracks() }.getOrDefault(emptyList())
        if (recent.isNotEmpty()) {
            add(
                browsableItem(
                    mediaId = RECENT_ID,
                    title = "Recently played",
                    subtitle = trackCountLabel(recent.size),
                ),
            )
        }
        add(
            browsableItem(
                mediaId = PLAYLISTS_ID,
                title = "Playlists",
                subtitle = "Your saved mixes and playlists",
            ),
        )
        add(
            browsableItem(
                mediaId = DOWNLOADS_ID,
                title = "Downloads",
                subtitle = "Music available offline",
                playable = true,
            ),
        )
        runCatching { playlistRepository.getLocalSongs() }.getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() }
            ?.let { local ->
                add(
                    browsableItem(
                        mediaId = "$PLAYLIST_PREFIX${local.id}",
                        title = "On this device",
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
                    title = "Favorites",
                    subtitle = "${favorites.size} saved albums and playlists",
                ),
            )
        }
    }

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
        private const val MAX_CACHED_SEARCHES = 6
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
