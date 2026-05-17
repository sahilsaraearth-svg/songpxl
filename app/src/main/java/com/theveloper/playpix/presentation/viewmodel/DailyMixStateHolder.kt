package com.svara.music.presentation.viewmodel

import com.svara.music.data.DailyMixManager
import com.svara.music.data.model.Song
import com.svara.music.data.preferences.UserPreferencesRepository
import com.svara.music.data.streaming.StreamingRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Daily Mix and Your Mix state.
 * Fetches songs directly from the streaming API — no DB dependency.
 */
@Singleton
class DailyMixStateHolder @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val streamingRepository: StreamingRepository
) {
    private var scope: CoroutineScope? = null
    private var updateJob: Job? = null

    private val _dailyMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = _dailyMixSongs.asStateFlow()

    private val _yourMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val yourMixSongs: StateFlow<ImmutableList<Song>> = _yourMixSongs.asStateFlow()

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
    }

    fun removeFromDailyMix(songId: String) {
        _dailyMixSongs.update { it.filterNot { s -> s.id == songId }.toImmutableList() }
    }

    /**
     * Fetch trending songs from API and build daily/your mixes.
     */
    fun updateDailyMix(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        updateJob?.cancel()
        updateJob = scope?.launch(Dispatchers.IO) {
            var attempt = 0
            val maxAttempts = 5
            val retryDelayMs = 5_000L
            while (attempt < maxAttempts) {
                attempt++
                val allSongs: List<Song> = try {
                    streamingRepository.getTrendingSongs(limit = 50)
                } catch (_: Exception) {
                    emptyList()
                }

                if (allSongs.isNotEmpty()) {
                    val favoriteIds = try { favoriteSongIdsFlow.first() } catch (_: Exception) { emptySet() }

                    val mix = dailyMixManager.generateDailyMix(allSongs, favoriteIds)
                    _dailyMixSongs.value = mix.toImmutableList()
                    userPreferencesRepository.saveDailyMixSongIds(mix.map { it.id })

                    val yourMix = dailyMixManager.generateYourMix(allSongs, favoriteIds)
                    _yourMixSongs.value = yourMix.toImmutableList()
                    userPreferencesRepository.saveYourMixSongIds(yourMix.map { it.id })
                    return@launch
                }
                // API returned empty — retry after delay
                if (attempt < maxAttempts) delay(retryDelayMs)
            }
        }
    }

    /**
     * Loads persisted mix — if mixes are already populated from API, this is a no-op.
     * Kept for compatibility with PlayerViewModel callers.
     */
    fun loadPersistedDailyMix() {
        // No-op: mixes are populated via updateDailyMix() from the API.
        // DB-backed restoration is removed to avoid stale/empty results.
    }

    fun forceUpdate(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        scope?.launch {
            updateDailyMix(favoriteSongIdsFlow)
            userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
        }
    }

    fun checkAndUpdateIfNeeded(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        // Always refresh from API on every app open
        updateDailyMix(favoriteSongIdsFlow)
        scope?.launch {
            userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
        }
    }

    fun setDailyMixSongs(songs: List<Song>) {
        _dailyMixSongs.value = songs.toImmutableList()
        scope?.launch {
            userPreferencesRepository.saveDailyMixSongIds(songs.map { it.id })
        }
    }

    suspend fun getCandidatePool(
        allSongs: List<Song>,
        favoriteIds: Set<String>,
        maxSize: Int = 100
    ): List<Song> = dailyMixManager.generateDailyMix(allSongs, favoriteIds, maxSize)

    fun onCleared() {
        updateJob?.cancel()
        scope = null
    }
}
