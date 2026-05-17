package com.svara.music.data.jiosaavn

import com.svara.music.data.model.Album
import com.svara.music.data.model.Artist
import com.svara.music.data.model.ArtistRef
import com.svara.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Singleton
class JioSaavnRepository @Inject constructor(
    private val api: JioSaavnApiService,
) {
    companion object {
        private const val TAG = "JioSaavnRepo"
        private const val SONG_ID_OFFSET   = 12_000_000_000_000L
        private const val ALBUM_ID_OFFSET  = 13_000_000_000_000L
        private const val ARTIST_ID_OFFSET = 14_000_000_000_000L
        private const val PARENT_DIRECTORY = "/Cloud/JioSaavn"

        fun bestDownloadUrl(urls: List<JioSaavnQuality>): String {
            val preferredOrder = listOf("320kbps", "160kbps", "96kbps", "48kbps", "12kbps")
            for (quality in preferredOrder) {
                val match = urls.firstOrNull { it.quality == quality }
                if (match != null && match.url.isNotBlank()) return match.url
            }
            return urls.lastOrNull()?.url ?: ""
        }

        fun bestImageUrl(images: List<JioSaavnQuality>): String {
            val preferredOrder = listOf("500x500", "150x150", "50x50")
            for (quality in preferredOrder) {
                val match = images.firstOrNull { it.quality == quality }
                if (match != null && match.url.isNotBlank()) return match.url
            }
            return images.lastOrNull()?.url ?: ""
        }

        fun songId(saavnId: String): String =
            ("jiosaavn_$saavnId".hashCode().toLong().absoluteValue % 1_000_000_000L + SONG_ID_OFFSET).toString()

        private fun albumId(saavnId: String): Long =
            "jiosaavn_album_$saavnId".hashCode().toLong().absoluteValue % 1_000_000_000L + ALBUM_ID_OFFSET

        private fun artistId(saavnId: String): Long =
            "jiosaavn_artist_$saavnId".hashCode().toLong().absoluteValue % 1_000_000_000L + ARTIST_ID_OFFSET
    }

    suspend fun searchSongs(query: String, limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        try {
            val response = api.searchSongs(query = query, limit = limit)
            response.data?.results.orEmpty().map { it.toSong() }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: searchSongs failed for '$query'")
            emptyList()
        }
    }

    suspend fun searchSongsForGenre(genreTag: String, limit: Int = 50): List<Song> = withContext(Dispatchers.IO) {
        try {
            val response = api.searchSongs(query = genreTag, limit = limit)
            response.data?.results.orEmpty().map { it.toSong(overrideGenre = genreTag) }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: searchSongsForGenre failed for '$genreTag'")
            emptyList()
        }
    }

    suspend fun searchAlbums(query: String): List<Album> = withContext(Dispatchers.IO) {
        try {
            val response = api.searchAlbums(query = query)
            response.data?.results.orEmpty().map { it.toAlbum() }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: searchAlbums failed for '$query'")
            emptyList()
        }
    }

    suspend fun searchArtists(query: String): List<Artist> = withContext(Dispatchers.IO) {
        try {
            val response = api.searchArtists(query = query)
            response.data?.results.orEmpty().map { it.toArtist() }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: searchArtists failed for '$query'")
            emptyList()
        }
    }

    suspend fun getTrendingSongs(limit: Int = 30): List<Song> = withContext(Dispatchers.IO) {
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val queries = listOf(
            "top hits hindi 2024",
            "best bollywood songs",
            "latest hindi songs",
            "popular punjabi songs",
            "trending indian songs",
            "best english pop songs",
            "top romantic hindi songs",
            "viral songs india",
            "best rap hindi songs",
            "top party songs india",
            "90s bollywood hits",
            "best indie songs india",
            "top lofi hindi songs",
            "best dance songs india",
            "top devotional songs india"
        )
        val query = queries[dayOfYear % queries.size]
        try {
            val response = api.searchSongs(query = query, limit = limit)
            val songs = response.data?.results.orEmpty()
            val shuffled = songs.shuffled(java.util.Random(dayOfYear.toLong()))
            Timber.d("$TAG: getTrendingSongs returned ${shuffled.size} songs for '$query'")
            shuffled.map { it.toSong() }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: getTrendingSongs FAILED — ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────

    private fun JioSaavnSong.toSong(overrideGenre: String? = null): Song {
        val id = songId(this.id)
        val dbAlbumId  = albumId(album?.id ?: this.id)
        val primaryRef = artists?.primary?.firstOrNull()
        val dbArtistId = artistId(primaryRef?.id ?: this.id)

        val streamUrl  = bestDownloadUrl(downloadUrl)
        val artUrl     = bestImageUrl(image)
        val durationMs = (duration?.toLong() ?: 0L) * 1000L
        val yearInt    = year?.toIntOrNull() ?: 0

        val artistRefs = buildArtistRefs()
        val displayArtist = artistRefs.firstOrNull { it.isPrimary }?.name
            ?: artists?.primary?.firstOrNull()?.name ?: "Unknown Artist"

        return Song(
            id                = id,
            title             = name,
            artist            = displayArtist,
            artistId          = dbArtistId,
            artists           = artistRefs,
            album             = album?.name ?: "",
            albumId           = dbAlbumId,
            albumArtist       = null,
            path              = streamUrl,
            contentUriString  = "jiosaavn://${this.id}",
            albumArtUriString = artUrl.ifBlank { null },
            duration          = durationMs,
            genre             = overrideGenre ?: language,
            lyrics            = null,
            isFavorite        = false,
            trackNumber       = 0,
            discNumber        = null,
            year              = yearInt,
            dateAdded         = System.currentTimeMillis(),
            mimeType          = "audio/mpeg",
            bitrate           = 320_000,
            sampleRate        = null
        )
    }

    private fun JioSaavnSong.buildArtistRefs(): List<ArtistRef> {
        val primary = artists?.primary?.map { ref ->
            ArtistRef(id = artistId(ref.id), name = ref.name, isPrimary = true)
        } ?: emptyList()
        val featured = artists?.featured?.map { ref ->
            ArtistRef(id = artistId(ref.id), name = ref.name, isPrimary = false)
        } ?: emptyList()
        return (primary + featured).distinctBy { it.id }
    }

    private fun JioSaavnAlbum.toAlbum(): Album {
        val primaryArtist = artists?.primary?.firstOrNull()
        val artUrl = bestImageUrl(image)
        val yearInt = year?.toIntOrNull() ?: 0
        return Album(
            id                = albumId(id),
            title             = name,
            artist            = primaryArtist?.name ?: "",
            albumArtUriString = artUrl.ifBlank { null },
            songCount         = songs?.size ?: 0,
            dateAdded         = System.currentTimeMillis(),
            year              = yearInt,
            albumArtist       = null
        )
    }

    private fun JioSaavnArtistResult.toArtist(): Artist {
        val artUrl = bestImageUrl(image)
        return Artist(
            id        = artistId(id),
            name      = name,
            songCount = 0,
            imageUrl  = artUrl.ifBlank { null }
        )
    }
}
