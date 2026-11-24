package com.lastwave.app.data.radio

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * La directory radio-browser elenca migliaia di voci, alcune morte o doppie:
 * il decoder deve tenere solo le stazioni suonabili e non deve azzerare
 * l'intero elenco per una voce malformata.
 */
class RadioRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun station(
        uuid: String,
        name: String,
        url: String,
        lastCheckOk: Int = 1,
    ) = """
        {
          "stationuuid": "$uuid",
          "name": "$name",
          "url": "$url",
          "url_resolved": "$url",
          "lastcheckok": $lastCheckOk
        }
    """.trimIndent()

    @Test
    fun decode_dropsInvalidEntriesButKeepsTheRest() {
        // Voce JSON valida ma senza i campi obbligatori (stationuuid/name/url):
        // è il caso reale di un record rovinato nella directory.
        val body = "[" +
            station("1", "Rai Radio 1", "http://icestreaming.rai.it/1.mp3") + "," +
            "{\"stationuuid\":\"2\"}" +
            "]"

        val stations = decodeRadioStations(json, body)

        assertThat(stations.map { it.name }).containsExactly("Rai Radio 1")
    }

    @Test
    fun decode_hidesStationsReportedAsBroken() {
        val body = "[" +
            station("1", "Rai Radio 1", "http://icestreaming.rai.it/1.mp3", lastCheckOk = 1) + "," +
            station("2", "Rete Morta", "http://example.invalid/dead.mp3", lastCheckOk = 0) +
            "]"

        val stations = decodeRadioStations(json, body)

        assertThat(stations.map { it.name }).containsExactly("Rai Radio 1")
    }

    @Test
    fun decode_keepsOneRowPerStream() {
        val body = "[" +
            station("1", "Radio 24", "http://shoutcast2.radio24.it:8000/;") + "," +
            station("2", "radio 24 ", "http://shoutcast2.radio24.it:8000/;") +
            "]"

        val stations = decodeRadioStations(json, body)

        assertThat(stations).hasSize(1)
    }

    @Test
    fun decode_ignoresNonArrayPayloads() {
        assertThat(decodeRadioStations(json, "{\"error\":\"rate limit\"}")).isEmpty()
        assertThat(decodeRadioStations(json, "")).isEmpty()
    }
}
