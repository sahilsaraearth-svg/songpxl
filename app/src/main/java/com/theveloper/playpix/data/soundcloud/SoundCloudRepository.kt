package com.svara.music.data.soundcloud

import com.svara.music.data.database.AlbumEntity
import com.svara.music.data.database.ArtistEntity
import com.svara.music.data.database.MusicDao
import com.svara.music.data.database.SongArtistCrossRef
import com.svara.music.data.database.SongEntity
import com.svara.music.data.database.SourceType
import com.svara.music.data.database.serializeArtistRefs
import com.svara.music.data.database.toSong
import com.svara.music.data.model.ArtistRef
import com.svara.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Fallback streaming repository using SoundCloud API v2.
 *
 * Used when JioSaavn returns 0 results for a given search query.
 *
 * ID strategy:
 *   Songs   → hash of "sc_<trackId>"  + SONG_ID_OFFSET   (15T range)
 *   Artists → hash of "sc_<userId>"   + ARTIST_ID_OFFSET (16T range)
 *   Albums  → fixed "sc_singles"      + ALBUM_ID_OFFSET  (17T range) — SoundCloud has no albums
 */
@Singleton
class SoundCloudRepository @Inject constructor(
    private val api: SoundCloudApiService,
    private val musicDao: MusicDao,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SoundCloudRepo"

        // Known working client_id scraped from SoundCloud JS (updated periodically)
        const val CLIENT_ID = "iZIs9mchVcX5lhVRyQGGAYlNPVlb1nQT"

        private const val SONG_ID_OFFSET   = 15_000_000_000_000L
        private const val ALBUM_ID_OFFSET  = 17_000_000_000_000L
        private const val ARTIST_ID_OFFSET = 16_000_000_000_000L
        private const val PARENT_DIRECTORY = "/Cloud/SoundCloud"
        private const val SC_SINGLES_ALBUM = "SoundCloud Singles"

        private fun songDbId(trackId: Long): Long =
            "sc_$trackId".hashCode().toLong().absoluteValue % 1_000_000_000L + SONG_ID_OFFSET

        private fun artistDbId(userId: Long): Long =
            "sc_$userId".hashCode().toLong().absoluteValue % 1_000_000_000L + ARTIST_ID_OFFSET

        private fun albumDbId(userId: Long): Long =
            "sc_album_$userId".hashCode().toLong().absoluteValue % 1_000_000_000L + ALBUM_ID_OFFSET

        /** Upgrade artwork URL to 500x500 from SoundCloud's default large (200x200). */
        private fun largeArtwork(url: String?): String? =
            url?.replace("-large.", "-t500x500.")
    }

    /** Search SoundCloud for tracks matching [query]. Returns mapped Song list. */
    suspend fun searchSongs(query: String, limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        try {
            val response = api.searchTracks(
                query = query,
                clientId = CLIENT_ID,
                limit = limit
            )
            val tracks = response.collection
            if (tracks.isEmpty()) return@withContext emptyList()

            val entities = tracks.map { it.toSongEntity() }
            cacheEntities(entities, tracks)
            entities.map { it.toSong() }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: searchSongs failed for query='$query'")
            emptyList()
        }
    }

    /**
     * Resolves the actual stream URL for a SoundCloud track.
     * SoundCloud uses signed URLs that expire — this must be called at playback time.
     *
     * The resolved URL is an HLS or progressive MP3 stream URL.
     */
    suspend fun resolveStreamUrl(transcodingUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val urlWithClientId = "$transcodingUrl?client_id=$CLIENT_ID"
            val request = Request.Builder().url(urlWithClientId).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("$TAG: resolveStreamUrl HTTP ${response.code} for $transcodingUrl")
                return@withContext null
            }
            val body = response.body?.string() ?: return@withContext null
            // Response is {"url": "https://..."} — extract the URL
            val urlMatch = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)
            urlMatch?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: resolveStreamUrl failed")
            null
        }
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────

    private fun SoundCloudTrack.toSongEntity(): SongEntity {
        val dbSongId   = songDbId(id)
        val dbArtistId = artistDbId(user.id)
        val dbAlbumId  = albumDbId(user.id)

        // Prefer progressive MP3 transcoding (direct stream, no HLS segments)
        val transcodingUrl = media?.transcodings
            ?.firstOrNull { it.format.protocol == "progressive" && it.format.mime_type.contains("mpeg") }
            ?.url
            ?: media?.transcodings?.firstOrNull { it.format.protocol == "hls" }?.url
            ?: ""

        // path stores the transcoding URL which needs to be resolved to a signed URL at playback time
        val streamPath = if (transcodingUrl.isNotBlank()) "$transcodingUrl?client_id=$CLIENT_ID" else ""

        val artUrl = largeArtwork(artwork_url) ?: ""
        val artistName = publisher_metadata?.artist?.ifBlank { null } ?: user.username

        val artistRef = ArtistRef(id = dbArtistId, name = artistName, isPrimary = true)
        val artistsJson = serializeArtistRefs(listOf(artistRef))

        return SongEntity(
            id                  = dbSongId,
            title               = title,
            artistName          = artistName,
            artistId            = dbArtistId,
            albumArtist         = null,
            albumName           = publisher_metadata?.album_title ?: SC_SINGLES_ALBUM,
            albumId             = dbAlbumId,
            contentUriString    = "soundcloud://$id",
            albumArtUriString   = artUrl.ifBlank { null },
            duration            = duration,
            genre               = genre,
            filePath            = streamPath,       // transcoding URL (resolved at playback if needed)
            parentDirectoryPath = PARENT_DIRECTORY,
            isFavorite          = false,
            lyrics              = null,
            trackNumber         = 0,
            discNumber          = null,
            year                = release_date?.take(4)?.toIntOrNull() ?: 0,
            dateAdded           = System.currentTimeMillis(),
            mimeType            = "audio/mpeg",
            bitrate             = null,
            sampleRate          = null,
            artistsJson         = artistsJson,
            sourceType          = SourceType.SOUNDCLOUD
        )
    }

    // ── Cache helpers ───────────────────────────────────────────────────────

    private suspend fun cacheEntities(entities: List<SongEntity>, tracks: List<SoundCloudTrack>) {
        try {
            val artists = tracks.map { track ->
                val artistName = track.publisher_metadata?.artist?.ifBlank { null } ?: track.user.username
                ArtistEntity(
                    id         = artistDbId(track.user.id),
                    name       = artistName,
                    trackCount = 0,
                    imageUrl   = largeArtwork(track.user.avatar_url)
                )
            }.distinctBy { it.id }

            val albums = tracks.map { track ->
                val artistName = track.publisher_metadata?.artist?.ifBlank { null } ?: track.user.username
                AlbumEntity(
                    id               = albumDbId(track.user.id),
                    title            = track.publisher_metadata?.album_title ?: SC_SINGLES_ALBUM,
                    artistName       = artistName,
                    artistId         = artistDbId(track.user.id),
                    albumArtUriString = largeArtwork(track.artwork_url),
                    songCount        = 0,
                    dateAdded        = System.currentTimeMillis(),
                    year             = track.release_date?.take(4)?.toIntOrNull() ?: 0,
                    albumArtist      = null
                )
            }.distinctBy { it.id }

            val crossRefs = tracks.map { track ->
                SongArtistCrossRef(
                    songId    = songDbId(track.id),
                    artistId  = artistDbId(track.user.id),
                    isPrimary = true
                )
            }

            musicDao.insertArtists(artists)
            musicDao.insertAlbums(albums)
            musicDao.insertSongs(entities)
            musicDao.insertSongArtistCrossRefs(crossRefs)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: cacheEntities failed")
        }
    }
}
