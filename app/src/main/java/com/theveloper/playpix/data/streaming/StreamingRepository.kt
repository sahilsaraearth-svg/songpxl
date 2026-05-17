package com.svara.music.data.streaming

import com.svara.music.data.itunes.ItunesRepository
import com.svara.music.data.jiosaavn.JioSaavnRepository
import com.svara.music.data.model.Album
import com.svara.music.data.model.Artist
import com.svara.music.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingRepository @Inject constructor(
    private val jioSaavn: JioSaavnRepository,
    private val iTunes: ItunesRepository,
) {
    companion object {
        private const val TAG = "StreamingRepo"
    }

    /**
     * Search songs. JioSaavn first — iTunes fallback only if JioSaavn returns nothing.
     */
    suspend fun searchSongs(query: String, limit: Int = 20): List<Song> {
        val jioResults = try {
            jioSaavn.searchSongs(query = query, limit = limit)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: JioSaavn searchSongs failed")
            emptyList()
        }
        if (jioResults.isNotEmpty()) {
            Timber.d("$TAG: JioSaavn → ${jioResults.size} songs for '$query'")
            return jioResults
        }
        Timber.d("$TAG: JioSaavn 0 results — trying iTunes for '$query'")
        return try {
            val itunesResults = iTunes.searchSongs(query = query, limit = limit)
            Timber.d("$TAG: iTunes → ${itunesResults.size} songs for '$query'")
            itunesResults
        } catch (e: Exception) {
            Timber.w(e, "$TAG: iTunes searchSongs also failed")
            emptyList()
        }
    }

    /** Search albums — JioSaavn only. */
    suspend fun searchAlbums(query: String): List<Album> = try {
        jioSaavn.searchAlbums(query = query)
    } catch (e: Exception) {
        Timber.w(e, "$TAG: searchAlbums failed")
        emptyList()
    }

    /** Search artists — JioSaavn only. */
    suspend fun searchArtists(query: String): List<Artist> = try {
        jioSaavn.searchArtists(query = query)
    } catch (e: Exception) {
        Timber.w(e, "$TAG: searchArtists failed")
        emptyList()
    }

    /**
     * Fetch trending songs — JioSaavn + iTunes in parallel, merged.
     * Falls back to iTunes alone if JioSaavn returns nothing.
     */
    suspend fun getTrendingSongs(limit: Int = 30): List<Song> = coroutineScope {
        val jioDeferred = async {
            try { jioSaavn.getTrendingSongs(limit = limit) }
            catch (e: Exception) {
                Timber.w(e, "$TAG: JioSaavn trending failed")
                emptyList()
            }
        }
        val itunesDeferred = async {
            try { iTunes.searchSongs(query = "top hits 2024", limit = limit / 2) }
            catch (e: Exception) {
                Timber.w(e, "$TAG: iTunes trending failed")
                emptyList()
            }
        }

        val jioSongs = jioDeferred.await()
        val itunesSongs = itunesDeferred.await()

        val merged = if (jioSongs.isNotEmpty()) {
            // JioSaavn primary, iTunes fills remaining slots with unique IDs
            val jioIds = jioSongs.map { it.id }.toSet()
            val extra = itunesSongs.filter { it.id !in jioIds }
            (jioSongs + extra).take(limit)
        } else {
            // JioSaavn empty — use iTunes only
            Timber.w("$TAG: JioSaavn returned 0 trending — using iTunes fallback")
            itunesSongs.take(limit)
        }

        Timber.d("$TAG: getTrendingSongs → ${merged.size} songs (jio=${jioSongs.size}, itunes=${itunesSongs.size})")
        merged
    }

    /**
     * Fetch songs for a genre tag.
     */
    suspend fun searchSongsForGenre(genreTag: String, limit: Int = 50): List<Song> = try {
        jioSaavn.searchSongsForGenre(genreTag = genreTag, limit = limit)
    } catch (e: Exception) {
        Timber.w(e, "$TAG: searchSongsForGenre failed for '$genreTag'")
        emptyList()
    }
}
