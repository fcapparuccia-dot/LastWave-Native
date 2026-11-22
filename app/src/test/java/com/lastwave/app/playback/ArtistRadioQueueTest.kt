package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The car's radio button is only lit while [MusicPlayerState.isArtistRadioQueue]
 * holds, so the label every artist-radio path publishes is the contract.
 */
class ArtistRadioQueueTest {

    private fun state(label: String, currentArtist: String = "Seed Artist") = MusicPlayerState(
        current = PlayableTrack(title = "Song", artist = currentArtist),
        queue = listOf(PlayableTrack(title = "Song", artist = currentArtist)),
        currentIndex = 0,
        sourceLabel = label,
    )

    @Test
    fun `artist radio label is shared by every entry point`() {
        assertThat(artistRadioLabel("Daft Punk")).isEqualTo("Daft Punk Radio")
        assertThat(artistRadioLabel("  Daft Punk  ")).isEqualTo("Daft Punk Radio")
        assertThat(state(artistRadioLabel("Daft Punk")).isArtistRadioQueue).isTrue()
    }

    @Test
    fun `radio stays lit when the queue moves on to another artist`() {
        val radio = state(artistRadioLabel("Daft Punk"), currentArtist = "Kavinsky")

        assertThat(radio.isArtistRadioQueue).isTrue()
    }

    @Test
    fun `ordinary queues leave the radio button dim`() {
        listOf("LastWave", "Popular now", "Recently played", "Discover", "Podcasts", "For You")
            .forEach { label ->
                assertThat(state(label).isArtistRadioQueue).isFalse()
            }
    }
}
