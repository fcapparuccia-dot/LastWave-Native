package com.lastwave.app.data.radio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Una stazione radio dalla directory pubblica radio-browser.info. */
@Serializable
data class RadioStation(
    /** Id globale della stazione nella directory. */
    @SerialName("stationuuid")
    val stationUuid: String,
    /** Nome leggibile della stazione. */
    val name: String,
    /** URL dello stream fornito dalla stazione (può essere un redirect/playlist). */
    val url: String,
    /** URL diretto risolto dalla directory (preferito per la riproduzione). */
    @SerialName("url_resolved")
    val urlResolved: String? = null,
    /** Icona della stazione (PNG/JPG). */
    @SerialName("favicon")
    val favicon: String? = null,
    /** Tag/generi separati da virgola. */
    val tags: String? = null,
    /** Codice paese ISO 3166-1 alpha-2 (es. "IT"). */
    @SerialName("countrycode")
    val countryCode: String? = null,
    /** Stato/regione all'interno del paese. */
    val state: String? = null,
    /** Lingue parlate. */
    val language: String? = null,
    /** Codec audio indicato dalla directory. */
    val codec: String? = null,
    /** Bitrate indicato dalla directory. */
    val bitrate: Int? = null,
    /** Numero di voti (popolarità). */
    @SerialName("votes")
    val votes: Int? = null,
    /** HLS: la directory lo espone come intero 0/1 (non boolean). */
    @SerialName("hls")
    val hls: Int? = null,
    /** Ultimo controllo della directory: 1 = funzionante, 0 = no (intero, non boolean). */
    @SerialName("lastcheckok")
    val lastCheckOk: Int? = null,
) {
    /** URL migliore da riprodurre: stream risolto, altrimenti URL grezzo. */
    val playableUrl: String
        get() = urlResolved?.takeIf { it.isNotBlank() } ?: url

    /** True quando lo stream è una playlist HLS e non un flusso progressivo. */
    val isHlsStream: Boolean
        get() = hls == 1 || playableUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /**
     * MIME valido per ExoPlayer. La directory fornisce "MP3"/"AAC"/…: "audio/mp3"
     * non è un MIME standard e faceva fallire la creazione del MediaItem, quindi
     * i codec noti vengono mappati e gli altri lasciano che ExoPlayer lo inferisca.
     * HLS va riconosciuto prima del codec: la directory lo segnala con hls=1 e
     * spesso riporta codec AAC o UNKNOWN, che produrrebbe un MIME audio su una
     * playlist e farebbe partire lo stream come file progressivo.
     */
    val mimeType: String?
        get() {
            if (isHlsStream) return "application/x-mpegURL"
            return when (codec?.trim()?.uppercase()) {
                "MP3" -> "audio/mpeg"
                "AAC", "AAC+", "AACPLUS" -> "audio/aac"
                "OGG", "OGG VORBIS", "VORBIS" -> "audio/ogg"
                "OPUS" -> "audio/opus"
                "FLAC" -> "audio/flac"
                "HLS" -> "application/x-mpegURL"
                else -> null
            }
        }

    /** Genere/tag accorciato per la UI. */
    val genreDisplay: String
        get() = tags?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: state?.takeIf { it.isNotBlank() }
            ?: countryCode?.takeIf { it.isNotBlank() }
            ?: "Radio"

    /** Posizione breve per il sottotitolo (stato e paese). */
    val locationDisplay: String
        get() = listOfNotNull(
            state?.takeIf { it.isNotBlank() },
            countryCode?.takeIf { it.isNotBlank() },
        ).joinToString(", ")

    /**
     * Filtro unico della lista radio: la schermata telefono e la ricerca in
     * Android Auto devono trovare le stesse stazioni per lo stesso testo.
     */
    fun matchesSearch(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return name.contains(needle, ignoreCase = true) ||
            tags?.contains(needle, ignoreCase = true) == true ||
            locationDisplay.contains(needle, ignoreCase = true)
    }
}
