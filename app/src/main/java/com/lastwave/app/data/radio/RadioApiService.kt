package com.lastwave.app.data.radio

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap

/**
 * Directory pubblica radio-browser.info (pubblica, senza chiave API).
 *
 * Restituisce il body grezzo (`ResponseBody`) come [com.lastwave.app.data.network.LastFmApiService]:
 * il repository decodifica con kotlinx.serialization, quindi non serve un
 * converter factory sul Retrofit condiviso.
 */
interface RadioApiService {

    /**
     * Cerca stazioni. Esempio:
     * `countrycode=IT&order=votes&reverse=true&format=json&limit=50`.
     */
    @GET("json/stations/search")
    suspend fun searchStations(@QueryMap params: Map<String, String>): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://de1.api.radio-browser.info/"
    }
}