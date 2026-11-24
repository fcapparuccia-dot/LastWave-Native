package com.lastwave.app.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.radio.RadioRepository
import com.lastwave.app.data.radio.RadioStation
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class RadioFmViewModel @Inject constructor(
    private val radioRepository: RadioRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** [forceRefresh] ignora la cache del repository e rilegge la directory. */
    fun refresh(forceRefresh: Boolean = false) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                _stations.value = radioRepository.getItalianStations(forceRefresh = forceRefresh)
            } finally {
                _loading.value = false
            }
        }
    }

    /** Avvia lo stream diretto della stazione (nessuna risoluzione YouTube/InnerTube). */
    fun playStation(station: RadioStation) {
        val track = PlayableTrack(
            title = station.name,
            artist = station.genreDisplay,
            album = station.countryCode,
            artworkUrl = station.favicon,
            playbackUrl = station.playableUrl,
            playbackMimeType = station.mimeType,
        )
        viewModelScope.launch {
            musicPlayer.playQueue(listOf(track), sourceLabel = "Radio FM")
        }
    }
}
