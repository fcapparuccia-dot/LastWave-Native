package com.lastwave.app.data.playlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMediaFavoritesTest {
    @Test
    fun savesAndPersistsFavoriteCollectionsByTypeAndEntityId() = runTest {
        val store = MemoryPreferences()
        val favorites = CarMediaFavorites(store)
        val album = CarMediaFavorite(
            type = "album",
            title = "Album",
            artist = "Artist",
            entityId = "MPRE123",
        )
        val playlist = CarMediaFavorite(
            type = "playlist",
            title = "Album",
            artist = "Artist",
            entityId = "MPRE123",
        )

        assertTrue(favorites.save(album))
        assertTrue(favorites.save(playlist))
        assertFalse(favorites.save(album))
        assertEquals(listOf(album, playlist), CarMediaFavorites(store).getAll())
        assertTrue(favorites.contains("album", "MPRE123"))
        assertTrue(favorites.remove("album", "MPRE123"))
        assertEquals(listOf(playlist), favorites.getAll())
    }

    @Test
    fun removingTheLastFavoriteClearsTheStoredEntry() = runTest {
        val store = MemoryPreferences()
        val favorites = CarMediaFavorites(store)
        val favorite = CarMediaFavorite(type = "playlist", entityId = "PL123", title = "Mix")
        favorites.save(favorite)

        assertTrue(favorites.remove("playlist", "PL123"))
        assertFalse(favorites.remove("playlist", "PL123"))
        assertEquals(emptyList<CarMediaFavorite>(), favorites.getAll())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
