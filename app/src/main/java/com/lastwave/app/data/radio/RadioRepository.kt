package com.lastwave.app.data.radio

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.parseToJsonElement

/**
 * Repository per le stazioni FM dalla directory radio-browser.info.
 *
 * Non c'è cache su disco: ogni apertura rilegge la directory, così telefono e
 * auto vedono lo stato online/offline aggiornato. Qualsiasi errore di rete o di
 * parsing degrada a una lista vuota (la UI non mostra errore, solo niente da
 * riprodurre).
 */
@Singleton
class RadioRepository @Inject constructor(
    private val api: RadioApiService,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "RadioRepository"
        const val DEFAULT_COUNTRY = "IT"
        const val LIMIT = 50
    }

    /** Stazioni italiane ordinate per popolarità (voti). */
    suspend fun getItalianStations(): List<RadioStation> =
        getStations(countryCode = DEFAULT_COUNTRY)

    /** Stazioni per un codice paese ISO 3166-1 alpha-2. */
    suspend fun getStations(countryCode: String): List<RadioStation> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "countrycode" to countryCode,
            "order" to "votes",
            "reverse" to "true",
            "format" to "json",
            "limit" to LIMIT.toString(),
        )
        runCatching {
            val response = api.searchStations(params)
            if (!response.isSuccessful) {
                Log.w(TAG, "Radio directory HTTP ${response.code} for $countryCode")
                emptyList()
            } else {
                val body = response.body()?.string().orEmpty()
                if (body.isBlank()) {
                    emptyList()
                } else {
                    val element = json.parseToJsonElement(body)
                    if (element is JsonArray) {
                        element.map { json.decodeFromString(RadioStation.serializer(), it.toString()) }
                    } else {
                        emptyList()
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to fetch radio stations for $countryCode", error)
        }.getOrDefault(emptyList())
    }

    /** Trova una stazione dal suo URL di stream (usato da Android Auto per riprendere una stazione). */
    suspend fun findStationByUrl(stations: List<RadioStation>, streamUrl: String): RadioStation? =
        stations.firstOrNull { it.playableUrl.equals(streamUrl, ignoreCase = true) }
            ?: stations.firstOrNull { it.url.equals(streamUrl, ignoreCase = true) }
}