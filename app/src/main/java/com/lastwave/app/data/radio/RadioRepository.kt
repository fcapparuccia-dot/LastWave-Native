package com.lastwave.app.data.radio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for FM radio stations from the radio-browser.info directory.
 *
 * Results are not cached to disk: each open re-fetches the directory so the
 * car and phone always see the current online/offline state. Network failures
 * degrade gracefully to an empty list.
 */
@Singleton
class RadioRepository @Inject constructor(
    private val api: RadioApiService,
    private val client: OkHttpClient,
) {
    private companion object {
        const val TAG = "RadioRepository"
        const val DEFAULT_COUNTRY = "IT"
        const val LIMIT = 50
    }

    /** Italian stations ordered by popularity (votes). */
    suspend fun getItalianStations(): List<RadioStation> =
        getStations(countryCode = DEFAULT_COUNTRY)

    /** Stations for a given ISO 3166-1 alpha-2 country code. */
    suspend fun getStations(countryCode: String): List<RadioStation> =
        withContext(Dispatchers.IO) {
            val url = HttpUrl.parse(RadioApiService.BASE_URL)
                ?.newBuilder()
                .addPathSegment("json")
                .addPathSegment("stations")
                .addPathSegment("search")
                .addQueryParameter("countrycode", countryCode)
                .addQueryParameter("order", "votes")
                .addQueryParameter("reverse", "true")
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", LIMIT.toString())
                .build()
                ?: return@withContext emptyList()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LastWave/5.2 (radio FM)")
                .header("Accept", "application/json")
                .build()

            runCatching {
                client.newCall(request).execute()
            }.onFailure { error ->
                Log.w(TAG, "Failed to fetch radio stations for $countryCode", error)
            }.getOrNull()?.use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.let { body ->
                        runCatching {
                            RadioApiService.json.decodeFromString(
                                kotlinx.serialization.json.JsonArray.serializer(),
                                body,
                            )
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to parse radio stations JSON", error)
                        }.getOrDefault(emptyList())
                    } ?: emptyList()
                } else {
                    Log.w(TAG, "Radio directory HTTP ${response.code} for $countryCode")
                    emptyList()
                }
            } ?: emptyList()
        }

    /** Find a station by its exact stream URL (used by Android Auto to resume a station). */
    suspend fun findStationByUrl(stations: List<RadioStation>, streamUrl: String): RadioStation? =
        stations.firstOrNull { it.playableUrl.equals(streamUrl, ignoreCase = true) }
            ?: stations.firstOrNull { it.url.equals(streamUrl, ignoreCase = true) }
}
