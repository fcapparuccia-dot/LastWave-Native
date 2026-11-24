package com.lastwave.app.data.radio

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Repository per le stazioni FM dalla directory radio-browser.info.
 *
 * La directory contiene oltre un migliaio di stazioni italiane: rileggerle a
 * ogni apertura della schermata costerebbe centinaia di KB, quindi la lista
 * resta in memoria per [CACHE_TTL_MS]. Un errore di rete non svuota una lista
 * già caricata (la schermata continua a mostrare le stazioni finché un refresh
 * esplicito non le aggiorna).
 */
@Singleton
class RadioRepository @Inject constructor(
    private val api: RadioApiService,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheLock = Any()
    private var cachedStations: List<RadioStation> = emptyList()
    private var cachedAtMs = 0L

    private companion object {
        const val TAG = "RadioRepository"
        const val DEFAULT_COUNTRY = "IT"
        /** Copre tutte le stazioni italiane funzionanti elencate dalla directory. */
        const val LIMIT = 1000
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }

    /** Stazioni italiane ordinate per ascolti (i network nazionali in cima). */
    suspend fun getItalianStations(forceRefresh: Boolean = false): List<RadioStation> =
        getStations(countryCode = DEFAULT_COUNTRY, forceRefresh = forceRefresh)

    /** Stazioni per un codice paese ISO 3166-1 alpha-2. */
    suspend fun getStations(
        countryCode: String,
        forceRefresh: Boolean = false,
    ): List<RadioStation> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = synchronized(cacheLock) {
                cachedStations.takeIf {
                    it.isNotEmpty() && System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS
                }
            }
            if (cached != null) return@withContext cached
        }
        val params = mapOf(
            "countrycode" to countryCode,
            // Senza hidebroken la directory restituisce anche le stazioni che
            // l'ultimo controllo ha trovato morte: l'elenco si riempie di stream
            // che non possono partire.
            "hidebroken" to "true",
            // Gli ascolti mettono davanti i network realmente usati; l'ordine
            // per voti lasciava in cima stazioni vecchie o duplicate.
            "order" to "clickcount",
            "reverse" to "true",
            "format" to "json",
            "limit" to LIMIT.toString(),
        )
        runCatching {
            val response = api.searchStations(params)
            if (!response.isSuccessful) {
                Log.w(TAG, "Radio directory HTTP ${response.code()} for $countryCode")
                emptyList()
            } else {
                val body = response.body()?.string().orEmpty()
                decodeRadioStations(json, body)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to fetch radio stations for $countryCode", error)
        }.getOrDefault(emptyList()).also { stations ->
            if (stations.isNotEmpty()) {
                synchronized(cacheLock) {
                    cachedStations = stations
                    cachedAtMs = System.currentTimeMillis()
                }
            }
        }
    }

    /** Trova una stazione dal suo URL di stream (usato da Android Auto per riprendere una stazione). */
    suspend fun findStationByUrl(stations: List<RadioStation>, streamUrl: String): RadioStation? =
        stations.firstOrNull { it.playableUrl.equals(streamUrl, ignoreCase = true) }
            ?: stations.firstOrNull { it.url.equals(streamUrl, ignoreCase = true) }
}

/**
 * Decodifica il body JSON della directory tenendo solo stream suonabili: una
 * voce malformata non azzera l'elenco, le stazioni che l'ultimo controllo ha
 * trovato morte vengono scartate e i duplicati dello stesso stream (bitrate o
 * URL alternativi dello stesso network) restano una riga sola.
 */
internal fun decodeRadioStations(json: Json, body: String): List<RadioStation> {
    if (body.isBlank()) return emptyList()
    val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { entry ->
        runCatching {
            json.decodeFromString(RadioStation.serializer(), entry.toString())
        }.getOrNull()
    }
        .filter { it.lastCheckOk != 0 }
        .distinctBy { "${it.name.trim().lowercase()}|${it.playableUrl}" }
}
