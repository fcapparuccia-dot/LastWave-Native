package com.lastwave.app.data.playlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CarMediaFavorite(
    val type: String,
    val entityId: String,
    val title: String,
    val subtitle: String? = null,
    val artist: String? = null,
    val artworkUrl: String? = null,
)

@Singleton
class CarMediaFavorites @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAll(): List<CarMediaFavorite> =
        dataStore.data.first()[FAVORITES_KEY]
            ?.let { json.decodeFromString<List<CarMediaFavorite>>(it) }
            .orEmpty()

    suspend fun contains(type: String, entityId: String): Boolean =
        getAll().any { it.type == type && it.entityId == entityId }

    suspend fun save(favorite: CarMediaFavorite): Boolean {
        var added = false
        dataStore.edit { preferences ->
            val current = preferences[FAVORITES_KEY]
                ?.let { json.decodeFromString<List<CarMediaFavorite>>(it) }
                .orEmpty()
            if (current.none { it.type == favorite.type && it.entityId == favorite.entityId }) {
                preferences[FAVORITES_KEY] = json.encodeToString(current + favorite)
                added = true
            }
        }
        return added
    }

    suspend fun remove(type: String, entityId: String): Boolean {
        var removed = false
        dataStore.edit { preferences ->
            val current = preferences[FAVORITES_KEY]
                ?.let { json.decodeFromString<List<CarMediaFavorite>>(it) }
                .orEmpty()
            val updated = current.filterNot {
                it.type == type && it.entityId == entityId
            }
            removed = updated.size != current.size
            if (updated.isEmpty()) preferences.remove(FAVORITES_KEY)
            else preferences[FAVORITES_KEY] = json.encodeToString(updated)
        }
        return removed
    }

    companion object {
        private val FAVORITES_KEY = stringPreferencesKey("lw_car_media_favorites")
    }
}
