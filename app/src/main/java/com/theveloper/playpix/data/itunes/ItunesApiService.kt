package com.svara.music.data.itunes

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * iTunes Search API — free, no key required.
 * Base URL: https://itunes.apple.com/
 */
interface ItunesApiService {
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 20
    ): ItunesSearchResponse
}

data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesTrack> = emptyList()
)

data class ItunesTrack(
    val trackId: Long = 0L,
    val trackName: String = "",
    val artistName: String = "",
    val collectionName: String = "",
    val previewUrl: String? = null,        // 30-second AAC preview
    val artworkUrl100: String? = null,     // 100x100 cover art
    val trackTimeMillis: Long = 0L,
    val primaryGenreName: String? = null,
    val releaseDate: String? = null
)
