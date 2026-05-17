package com.svara.music.data.soundcloud

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for SoundCloud API v2.
 * Base URL: https://api-v2.soundcloud.com/
 *
 * Uses a client_id scraped from SoundCloud's public JS bundles.
 * No OAuth or user login required for search + stream URL resolution.
 */
interface SoundCloudApiService {

    /**
     * Search tracks by query.
     * Returns up to [limit] track results.
     */
    @GET("search/tracks")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("client_id") clientId: String,
        @Query("limit") limit: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("linked_partitioning") linkedPartitioning: Int = 1
    ): SoundCloudSearchResponse
}

// ── Response models ──────────────────────────────────────────────────────────

data class SoundCloudSearchResponse(
    val collection: List<SoundCloudTrack> = emptyList(),
    val total_results: Int = 0,
    val next_href: String? = null
)

data class SoundCloudTrack(
    val id: Long = 0L,
    val title: String = "",
    val duration: Long = 0L,           // milliseconds
    val genre: String? = null,
    val stream_url: String? = null,    // deprecated on v2 but still present sometimes
    val permalink_url: String = "",
    val artwork_url: String? = null,
    val user: SoundCloudUser = SoundCloudUser(),
    val media: SoundCloudMedia? = null,
    val publisher_metadata: SoundCloudPublisherMetadata? = null,
    val release_date: String? = null,
    val created_at: String? = null
)

data class SoundCloudUser(
    val id: Long = 0L,
    val username: String = "",
    val avatar_url: String? = null,
    val permalink_url: String = ""
)

data class SoundCloudMedia(
    val transcodings: List<SoundCloudTranscoding> = emptyList()
)

data class SoundCloudTranscoding(
    val url: String = "",
    val preset: String = "",           // e.g. "mp3_0_1", "opus_0_0"
    val format: SoundCloudFormat = SoundCloudFormat(),
    val quality: String = ""           // e.g. "sq", "hq"
)

data class SoundCloudFormat(
    val protocol: String = "",         // "hls" or "progressive"
    val mime_type: String = ""
)

data class SoundCloudPublisherMetadata(
    val id: Long = 0L,
    val urn: String = "",
    val artist: String? = null,
    val album_title: String? = null,
    val release_title: String? = null
)
