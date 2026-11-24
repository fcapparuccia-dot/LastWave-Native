package com.lastwave.app.data.radio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadioStationTest {

    private fun station(
        codec: String?,
        hls: Int? = 0,
        url: String = "https://example.com/live",
    ) = RadioStation(
        stationUuid = "uuid",
        name = "Station",
        url = url,
        codec = codec,
        hls = hls,
    )

    @Test
    fun mimeType_mapsKnownCodecs() {
        assertThat(station("MP3").mimeType).isEqualTo("audio/mpeg")
        assertThat(station("AAC+").mimeType).isEqualTo("audio/aac")
        assertThat(station("UNKNOWN").mimeType).isNull()
    }

    @Test
    fun mimeType_hlsFlagWinsOverCodec() {
        assertThat(station("AAC", hls = 1).mimeType).isEqualTo("application/x-mpegURL")
        assertThat(station("UNKNOWN", hls = 1).mimeType).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun mimeType_playlistExtensionIsHls() {
        val playlist = station("AAC", hls = 0, url = "https://stream.rds.radio/audio/chunklist.m3u8")

        assertThat(playlist.isHlsStream).isTrue()
        assertThat(playlist.mimeType).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun playableUrl_prefersResolvedStream() {
        val resolved = RadioStation(
            stationUuid = "uuid",
            name = "Station",
            url = "http://example.com/old",
            urlResolved = "https://example.com/live",
        )

        assertThat(resolved.playableUrl).isEqualTo("https://example.com/live")
    }

    @Test
    fun matchesSearch_readsNameTagsAndLocation() {
        val rai = RadioStation(
            stationUuid = "uuid-rai",
            name = "Rai Radio 1",
            url = "https://example.com/live",
            tags = "news,talk",
            countryCode = "IT",
            state = "Lazio",
        )

        // Stesso filtro sul telefono e in auto: nome, tag e posizione.
        assertThat(rai.matchesSearch("rai")).isTrue()
        assertThat(rai.matchesSearch("TALK")).isTrue()
        assertThat(rai.matchesSearch("lazio")).isTrue()
        assertThat(rai.matchesSearch("  ")).isTrue()
        assertThat(rai.matchesSearch("deejay")).isFalse()
    }
}
