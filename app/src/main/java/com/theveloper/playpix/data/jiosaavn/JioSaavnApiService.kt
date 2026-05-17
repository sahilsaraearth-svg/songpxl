package com.svara.music.data.jiosaavn

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the JioSaavn unofficial API.
 * Base URL: https://jiosavan-api2.vercel.app/
 */
interface JioSaavnApiService {

    /** Search songs by query. Returns up to [limit] results. */
    @GET("api/search/songs")
    suspend fun searchSongs(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): JioSaavnSearchResponse

    /** Search albums by query. */
    @GET("api/search/albums")
    suspend fun searchAlbums(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 10
    ): JioSaavnAlbumSearchResponse

    /** Search artists by query. */
    @GET("api/search/artists")
    suspend fun searchArtists(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 10
    ): JioSaavnArtistSearchResponse

    /** Fetch song details by JioSaavn song ID. */
    @GET("api/songs/{id}")
    suspend fun getSong(
        @Path("id") songId: String
    ): JioSaavnSongDetailResponse
}

// ── Response models ──────────────────────────────────────────────────────────

data class JioSaavnSearchResponse(
    val success: Boolean = false,
    val data: JioSaavnSongData? = null
)

data class JioSaavnSongData(
    val total: Int = 0,
    val start: Int = 0,
    val results: List<JioSaavnSong> = emptyList()
)

data class JioSaavnAlbumSearchResponse(
    val success: Boolean = false,
    val data: JioSaavnAlbumData? = null
)

data class JioSaavnAlbumData(
    val total: Int = 0,
    val results: List<JioSaavnAlbum> = emptyList()
)

data class JioSaavnArtistSearchResponse(
    val success: Boolean = false,
    val data: JioSaavnArtistData? = null
)

data class JioSaavnArtistData(
    val total: Int = 0,
    val results: List<JioSaavnArtistResult> = emptyList()
)

data class JioSaavnSongDetailResponse(
    val success: Boolean = false,
    val data: List<JioSaavnSong> = emptyList()
)

data class JioSaavnSong(
    val id: String = "",
    val name: String = "",
    val year: String? = null,
    val releaseDate: String? = null,
    val duration: Int? = null,          // seconds as Int
    val label: String? = null,
    val explicitContent: Boolean = false,
    val playCount: Long? = null,
    val language: String? = null,
    val hasLyrics: Boolean? = null,
    val url: String = "",
    val copyright: String? = null,
    val album: JioSaavnAlbumRef? = null,
    val artists: JioSaavnArtistGroup? = null,
    val image: List<JioSaavnQuality> = emptyList(),
    val downloadUrl: List<JioSaavnQuality> = emptyList()
)

data class JioSaavnAlbumRef(
    val id: String = "",
    val name: String = "",
    val url: String = ""
)

data class JioSaavnArtistGroup(
    val primary: List<JioSaavnArtistRef> = emptyList(),
    val featured: List<JioSaavnArtistRef> = emptyList(),
    val all: List<JioSaavnArtistRef> = emptyList()
)

data class JioSaavnArtistRef(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val image: List<JioSaavnQuality> = emptyList(),
    val type: String = "",
    val role: String = ""
)

data class JioSaavnQuality(
    val quality: String = "",
    val url: String = ""   // new API uses "url" not "link"
)

data class JioSaavnAlbum(
    val id: String = "",
    val name: String = "",
    val year: String? = null,
    val playCount: Long? = null,
    val language: String? = null,
    val explicitContent: Boolean = false,
    val url: String = "",
    val primaryArtists: List<JioSaavnArtistRef> = emptyList(),
    val featuredArtists: List<JioSaavnArtistRef> = emptyList(),
    val artists: JioSaavnArtistGroup? = null,
    val image: List<JioSaavnQuality> = emptyList(),
    val songs: List<JioSaavnSong>? = null
)

data class JioSaavnArtistResult(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val image: List<JioSaavnQuality> = emptyList(),
    val type: String = ""
)
