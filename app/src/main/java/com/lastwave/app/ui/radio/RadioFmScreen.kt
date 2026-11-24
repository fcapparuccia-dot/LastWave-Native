package com.lastwave.app.ui.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.lastwave.app.data.radio.RadioStation

@Composable
fun RadioFmScreen(
    viewModel: RadioFmViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Radio FM") },
            )
        },
    ) { padding ->
        val stations by viewModel.stations.collectAsState()
        val loading by viewModel.loading.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.refresh()
        }

        if (loading && stations.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = padding.top, start = 16.dp, end = 16.dp)
                    .height(20.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = padding.top, start = 16.dp, end = 16.dp),
            ) {
                items(
                    items = stations,
                    key = { it.stationUuid },
                ) { station ->
                    RadioStationRow(
                        station = station,
                        onClick = { viewModel.playStation(station) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioStationRow(
    station: RadioStation,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (station.favicon != null) {
            AsyncImage(
                model = station.favicon,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
        } else {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.filled.Audio,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            val subtitle = buildString {
                appendIfNotBlank(station.genreDisplay)
                if (station.genreDisplay.isNotBlank() && station.locationDisplay.isNotBlank()) append(" • ")
                appendIfNotBlank(station.locationDisplay)
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
        androidx.compose.material.icons.filled.PlayArrow
    }
}

private fun StringBuilder.appendIfNotBlank(value: String?) {
    if (value?.isNotBlank() == true) append(value)
}
