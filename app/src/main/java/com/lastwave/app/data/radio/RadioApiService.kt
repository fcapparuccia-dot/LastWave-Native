package com.lastwave.app.data.radio

import kotlinx.serialization.json.Json
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap

/**
 * radio-browser.info public directory API.
 *
 * Public, no-auth API. Rate limits are generous for personal use but the app
 * still caches results to avoid hammering the directory on every open.
 * See https://www.radio-browser.info/ for terms and endpoints.
 */
interface RadioApiService {

    /**
     * Search stations. Example query:
     * `countrycode=IT&order=votes&reverse=true&format=json&limit=100`
     */
    @GET("json/stations/search")
    suspend fun searchStations(@QueryMap params: Map<String, String>): Response<List<RadioStation>>

    companion object {
        const val BASE_URL = "https://de1.api.radio-browser.info/"
        val json = Json { ignoreUnknownKeys = true }
    }
}
