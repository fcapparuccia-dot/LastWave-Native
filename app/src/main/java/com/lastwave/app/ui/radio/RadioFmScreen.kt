package com.lastwave.app.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.lastwave.app.R
import com.lastwave.app.data.radio.RadioStation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioFmScreen(
    viewModel: RadioFmViewModel = hiltViewModel(),
) {
    val stations by viewModel.stations.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteIds = remember(favorites) { favorites.mapTo(mutableSetOf()) { it.stationUuid } }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // La directory elenca centinaia di stazioni: senza filtro trovare un
    // network preciso richiede uno scroll lunghissimo.
    val visibleStations = remember(stations, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            stations
        } else {
            stations.filter { station ->
                station.name.contains(needle, ignoreCase = true) ||
                    station.tags?.contains(needle, ignoreCase = true) == true ||
                    station.locationDisplay.contains(needle, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.radio_fm_title)) },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh(forceRefresh = true) },
                        enabled = !loading,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.radio_fm_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.radio_fm_search_hint)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.radio_fm_clear_search),
                            )
                        }
                    }
                },
            )
            when {
                loading && stations.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                visibleStations.isEmpty() -> {
                    val message = if (stations.isEmpty()) {
                        R.string.radio_fm_empty
                    } else {
                        R.string.radio_fm_no_results
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(message),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        items(
                            items = visibleStations,
                            key = { it.stationUuid },
                        ) { station ->
                            RadioStationRow(
                                station = station,
                                isFavorite = station.stationUuid in favoriteIds,
                                onClick = { viewModel.playStation(station) },
                                onToggleFavorite = { viewModel.toggleFavorite(station) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioStationRow(
    station: RadioStation,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!station.favicon.isNullOrBlank()) {
            AsyncImage(
                model = station.favicon,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Audiotrack,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            val subtitle = buildString {
                if (station.genreDisplay.isNotBlank()) append(station.genreDisplay)
                if (station.genreDisplay.isNotBlank() && station.locationDisplay.isNotBlank()) append(" • ")
                if (station.locationDisplay.isNotBlank()) append(station.locationDisplay)
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.radio_fm_favorite_remove else R.string.radio_fm_favorite_add,
                ),
                tint = if (isFavorite) FavoriteHeart else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
        )
    }
}

/** Lo stesso rosa-rosso del cuore del player. */
private val FavoriteHeart = Color(0xFFE91E63)
