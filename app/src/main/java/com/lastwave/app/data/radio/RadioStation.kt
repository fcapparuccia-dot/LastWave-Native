package com.lastwave.app.data.radio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single radio station from the radio-browser.info directory. */
@Serializable
data class RadioStation(
    /** Globally unique station id from the directory. */
    val stationUuid: String,
    /** Human-readable station name. */
    val name: String,
    /** Stream URL as provided by the station owner (may be a playlist/redirect). */
    val url: String,
    /** Automatically resolved direct stream URL (preferred for playback). */
    @SerialName("url_resolved")
    val urlResolved: String? = null,
    /** Icon/picture representing the stream (PNG/JPG). */
    @SerialName("favicon")
    val favicon: String? = null,
    /** Comma-separated tags/genres. */
    val tags: String? = null,
    /** ISO 3166-1 alpha-2 country code (e.g. "IT"). */
    val countryCode: String? = null,
    /** State/region inside the country. */
    val state: String? = null,
    /** Spoken languages. */
    val language: String? = null,
    /** Audio codec reported by the directory. */
    val codec: String? = null,
    /** Bitrate reported by the directory. */
    val bitrate: Int? = null,
    /** Whether the stream uses HLS. */
    @SerialName("hls")
    val hls: Boolean? = null,
    /** Last check result from the directory. */
    @SerialName("lastcheckok")
    val lastCheckOk: Boolean? = null,
) {
    /** Best playable URL: resolved stream > raw URL. */
    val playableUrl: String
        get() = urlResolved.takeIf(String::isNotBlank) ?: url

    /** Genre/tags trimmed for display. */
    val genreDisplay: String
        get() = tags?.takeIf { it.isNotBlank() }?.let { it.split(",").first().trim() }
            ?: state.takeIf { it.isNotBlank() } ?: countryCode.takeIf { it.isNotBlank() } ?: "Radio"

    /** Short location string for the UI subtitle. */
    val locationDisplay: String
        get() = buildString {
            appendIfNotBlank(state)
            if (state.isNotNull() && countryCode.isNotNull()) append(", ")
            appendIfNotBlank(countryCode)
        }

    private fun String?.appendIfNotBlank(value: String?) {
        if (value?.isNotBlank() == true) append(value)
    }
}
