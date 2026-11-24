package com.lastwave.app.ui.radio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.radio.RadioRepository
import com.lastwave.app.data.radio.RadioStation
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RadioFmViewModel @Inject constructor(
    private val radioRepository: RadioRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private companion object {
        const val TAG = "RadioFmViewModel"
    }

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _stations.value = radioRepository.getItalianStations()
            } finally {
                _loading.value = false
            }
        }
    }

    fun playStation(station: RadioStation) {
        viewModelScope.launch {
            val track = PlayableTrack(
                title = station.name,
                artist = station.genreDisplay,
                album = station.countryCode,
                artworkUrl = station.favicon,
                playbackUrl = station.playableUrl,
                playbackMimeType = station.codec?.let { "audio/${it.lowercase()}" },
                durationMs = null,
            )
            musicPlayer.playQueue(listOf(track), sourceLabel = "Radio FM")
        }
    }
}
