package com.svara.music.data.itunes

import com.svara.music.data.model.ArtistRef
import com.svara.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Fetches English songs from the iTunes Search API (free, no key).
 * Used as fallback when JioSaavn returns no results.
 *
 * Note: previewUrl is a 30-second AAC preview — best available without auth.
 */
@Singleton
class ItunesRepository @Inject constructor(
    private val api: ItunesApiService
) {
    companion object {
        private const val TAG = "ItunesRepo"
        private const val SONG_ID_OFFSET   = 18_000_000_000_000L
        private const val ALBUM_ID_OFFSET  = 19_000_000_000_000L
        private const val ARTIST_ID_OFFSET = 20_000_000_000_000L

        private fun songId(trackId: Long): String =
            ("itunes_$trackId".hashCode().toLong().absoluteValue % 1_000_000_000L + SONG_ID_OFFSET).toString()

        private fun albumId(name: String): Long =
            "itunes_album_$name".hashCode().toLong().absoluteValue % 1_000_000_000L + ALBUM_ID_OFFSET

        private fun artistId(name: String): Long =
            "itunes_artist_$name".hashCode().toLong().absoluteValue % 1_000_000_000L + ARTIST_ID_OFFSET

        /** Upgrade artwork from 100x100 to 600x600 by replacing size token. */
        fun upgradeArtwork(url: String?): String? =
            url?.replace("100x100bb", "600x600bb")
    }

    suspend fun searchSongs(query: String, limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        try {
            val response = api.search(term = query, limit = limit)
            Timber.d("$TAG: iTunes returned ${response.resultCount} results for '$query'")
            response.results.mapNotNull { it.toSong() }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: searchSongs failed for '$query'")
            emptyList()
        }
    }

    private fun ItunesTrack.toSong(): Song? {
        val preview = previewUrl?.takeIf { it.isNotBlank() } ?: return null // skip tracks with no preview
        val artUrl  = upgradeArtwork(artworkUrl100)
        val dbArtistId = artistId(artistName)
        val dbAlbumId  = albumId(collectionName)
        val yearInt = releaseDate?.take(4)?.toIntOrNull() ?: 0

        return Song(
            id                = songId(trackId),
            title             = trackName,
            artist            = artistName,
            artistId          = dbArtistId,
            artists           = listOf(ArtistRef(id = dbArtistId, name = artistName, isPrimary = true)),
            album             = collectionName,
            albumId           = dbAlbumId,
            albumArtist       = null,
            path              = preview,                   // 30s AAC preview URL
            contentUriString  = "itunes://$trackId",
            albumArtUriString = artUrl,
            duration          = trackTimeMillis.takeIf { it > 0L } ?: 30_000L,
            genre             = primaryGenreName,
            lyrics            = null,
            isFavorite        = false,
            trackNumber       = 0,
            discNumber        = null,
            year              = yearInt,
            dateAdded         = System.currentTimeMillis(),
            mimeType          = "audio/aac",
            bitrate           = 256_000,
            sampleRate        = null
        )
    }
}
