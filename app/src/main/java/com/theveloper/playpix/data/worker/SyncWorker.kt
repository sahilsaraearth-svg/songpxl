package com.svara.music.data.worker

import android.content.Context
import android.os.Trace
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.svara.music.data.database.AlbumEntity
import com.svara.music.data.database.ArtistEntity
import com.svara.music.data.database.MusicDao
import com.svara.music.data.database.NeteaseDao
import com.svara.music.data.database.SongArtistCrossRef
import com.svara.music.data.database.SongEntity
import com.svara.music.data.database.SourceType
import com.svara.music.data.database.TelegramDao
import com.svara.music.data.database.resolveAlbumArtUri
import com.svara.music.data.database.serializeArtistRefs
import com.svara.music.data.media.AudioMetadataReader
import com.svara.music.data.model.ArtistRef
import com.svara.music.data.navidrome.NavidromeRepository
import com.svara.music.data.preferences.UserPreferencesRepository
import com.svara.music.data.streaming.StreamingRepository
import com.svara.music.data.repository.LyricsRepository
import com.svara.music.utils.LocalArtworkUri
import com.svara.music.utils.normalizeMetadataTextOrEmpty
import com.svara.music.utils.splitArtistsByDelimiters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class SyncMode {
    INCREMENTAL,
    FULL,
    REBUILD
}

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val musicDao: MusicDao,
        private val userPreferencesRepository: UserPreferencesRepository,
        private val lyricsRepository: LyricsRepository,
        private val telegramDao: TelegramDao,
        private val neteaseDao: NeteaseDao,
        private val navidromeRepository: NavidromeRepository,
        private val streamingRepository: StreamingRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                Trace.beginSection("SyncWorker.doWork")
                try {
                    val syncModeName =
                            inputData.getString(INPUT_SYNC_MODE) ?: SyncMode.INCREMENTAL.name
                    val syncMode = SyncMode.valueOf(syncModeName)

                    Timber.tag(TAG).i("Starting sync (Mode: $syncMode) — streaming-only mode, MediaStore scan disabled.")
                    val startTime = System.currentTimeMillis()

                    // --- CLOUD SYNC PHASE ---
                    // MediaStore / local file scanning is disabled. This worker only syncs
                    // Telegram, Netease, and Navidrome cloud sources into the unified DB.

                    val hasTelegramChannels = telegramDao.getAllChannels().first().isNotEmpty()
                    val neteaseCount = neteaseDao.getNeteaseCount()
                    val navidromeNeedsNetworkSync = navidromeRepository.isLoggedIn &&
                        (System.currentTimeMillis() - navidromeRepository.lastFullSyncTime >= NavidromeRepository.SYNC_THRESHOLD_MS)

                    val needsActiveCloudSync = hasTelegramChannels || neteaseCount > 0 || navidromeNeedsNetworkSync
                    if (needsActiveCloudSync) {
                        setProgress(workDataOf(PROGRESS_PHASE to SyncProgress.SyncPhase.SYNCING_CLOUD.ordinal))
                    }

                    if (hasTelegramChannels) {
                        syncTelegramData()
                    } else {
                        Log.d(TAG, "Skipping Telegram sync — no channels configured.")
                    }

                    if (neteaseCount > 0) {
                        syncNeteaseData()
                    } else {
                        Log.d(TAG, "Skipping Netease sync — no songs in local cache.")
                    }

                    if (navidromeRepository.isLoggedIn) {
                        syncNavidromeData()
                    } else {
                        Log.d(TAG, "Skipping Navidrome sync — not logged in.")
                    }

                    // Fetch trending JioSaavn songs so Library/Home are populated on every sync
                    try {
                        Timber.tag(TAG).i("Fetching JioSaavn trending songs...")
                        val trendingSongs = streamingRepository.getTrendingSongs(limit = 50)
                        Timber.tag(TAG).i("JioSaavn sync: cached ${trendingSongs.size} trending songs")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "JioSaavn trending fetch failed during sync")
                    }

                    userPreferencesRepository.setLastSyncTimestamp(System.currentTimeMillis())

                    val endTime = System.currentTimeMillis()
                    Timber.tag(TAG).i("Sync finished in ${endTime - startTime}ms.")

                    val finalTotalSongs = musicDao.getSongCount().first()
                    Result.success(workDataOf(OUTPUT_TOTAL_SONGS to finalTotalSongs.toLong()))
                } catch (e: Exception) {
                    Log.e(TAG, "Error during MediaStore synchronization", e)
                    Result.failure()
                } finally {
                    Trace.endSection() // End SyncWorker.doWork
                }
            }

    companion object {
        const val WORK_NAME = "com.svara.music.data.worker.SyncWorker"
        private const val TAG = "SyncWorker"
        const val INPUT_FORCE_METADATA = "input_force_metadata"
        const val INPUT_SYNC_MODE = "input_sync_mode"

        // Progress reporting constants
        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_PHASE = "progress_phase"
        const val OUTPUT_TOTAL_SONGS = "output_total_songs"
        private const val NETEASE_SONG_ID_OFFSET = 3_000_000_000_000L
        private const val NETEASE_ALBUM_ID_OFFSET = 4_000_000_000_000L
        private const val NETEASE_ARTIST_ID_OFFSET = 5_000_000_000_000L
        private const val NETEASE_PARENT_DIRECTORY = "/Cloud/Netease"
        private const val NETEASE_GENRE = "Netease Cloud"

        fun startUpSyncWork(deepScan: Boolean = false) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_FORCE_METADATA to deepScan,
                                        INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name
                                )
                        )
                        .build()

        fun incrementalSyncWork() =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(workDataOf(INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name))
                        .build()

        // Full rescans and rebuilds do heavy bulk writes to Room + the album art cache.
        // Requiring non-critical storage prevents partial/corrupt syncs when the device is
        // nearly full. Not applied to incremental/startup sync so the library still appears
        // immediately when the user opens the app.
        private val heavySyncConstraints: Constraints =
                Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build()

        fun fullSyncWork(deepScan: Boolean = false) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.FULL.name,
                                        INPUT_FORCE_METADATA to deepScan
                                )
                        )
                        .setConstraints(heavySyncConstraints)
                        .build()

        fun rebuildDatabaseWork() =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(workDataOf(INPUT_SYNC_MODE to SyncMode.REBUILD.name))
                        .setConstraints(heavySyncConstraints)
                        .build()
    }
    
    // Logic to sync Telegram songs into main DB with Unified Library Support
    private suspend fun syncTelegramData() {
        Log.i(TAG, "Syncing Telegram songs to main database (Unified Mode)...")
        try {
            val telegramSongs = telegramDao.getAllTelegramSongs().first()
            val channels = telegramDao.getAllChannels().first().associateBy { it.chatId }
            val existingUnifiedTelegramIds = musicDao.getAllTelegramSongIds()
            
            if (telegramSongs.isEmpty()) { 
                if (existingUnifiedTelegramIds.isNotEmpty()) {
                    musicDao.clearAllTelegramSongs()
                }
                Log.d(TAG, "No Telegram songs to sync.")
                return 
            }

            // 1. Pre-load Local Data for Merging
            val existingArtists = musicDao.getAllArtistsListRaw().associate { it.name.trim().lowercase() to it.id }
            val existingAlbums = musicDao.getAllAlbumsList(emptyList(), false, 0).associate { "${it.title.trim().lowercase()}_${it.artistName.trim().lowercase()}" to it.id }
            val existingArtistImageUrls = musicDao.getAllArtistsListRaw().associate { it.id to it.imageUrl }
            val nextArtistId = AtomicLong((musicDao.getMaxArtistId() ?: 0L) + 1)
            val delimiters = userPreferencesRepository.artistDelimitersFlow.first()
            val wordDelims = userPreferencesRepository.artistWordDelimitersFlow.first()

            val songsToInsert = mutableListOf<SongEntity>()
            val artistsToInsert = mutableMapOf<Long, ArtistEntity>() // Map to dedup by ID
            val albumsToInsert = mutableMapOf<Long, AlbumEntity>()   // Map to dedup by ID
            val crossRefsToInsert = mutableListOf<SongArtistCrossRef>()
            
            telegramSongs.forEach { tSong ->
                val channelName = channels[tSong.chatId]?.title ?: "Telegram Stream"
                // Synthetic negative ID for Song to check existence, but we want to merge metadata
                // We use negative IDs for songs to definitively identify them as Telegram-sourced in the DB
                // This prevents collision with MediaStore numeric IDs.
                val songId = -(tSong.id.hashCode().toLong().absoluteValue)
                val finalSongId = if (songId == 0L) -1L else songId
                
                // 2. Metadata Refinement (ID3 for Downloaded Files)
                var realTitle = tSong.title
                var realArtistName = tSong.artist
                var realAlbumName = channelName
                var realDateAdded = tSong.dateAdded
                var realYear = 0
                var realTrackNumber = 0
                var realDiscNumber: Int? = null
                var realAlbumArtist = "Telegram"
                var realGenre: String? = null
                var realLyrics: String? = null
                var realDuration = tSong.duration
                var realBitrate: Int? = null
                var realSampleRate: Int? = null
                var resolvedAlbumArtUri = tSong.resolveAlbumArtUri()
                
                val file = java.io.File(tSong.filePath)
                if (tSong.filePath.isNotEmpty() && file.exists()) {
                     try {
                        AudioMetadataReader.read(file, readArtwork = false)?.let { meta ->
                            if (!meta.title.isNullOrBlank()) realTitle = meta.title
                            if (!meta.artist.isNullOrBlank()) realArtistName = meta.artist
                            if (!meta.album.isNullOrBlank()) realAlbumName = meta.album
                            if (!meta.albumArtist.isNullOrBlank()) {
                                realAlbumArtist = meta.albumArtist
                            } else if (!realArtistName.isBlank()) {
                                realAlbumArtist = realArtistName
                            }
                            if (!meta.genre.isNullOrBlank()) realGenre = meta.genre
                            if (!meta.lyrics.isNullOrBlank()) realLyrics = meta.lyrics
                            if (meta.trackNumber != null) realTrackNumber = meta.trackNumber
                            if (meta.discNumber != null) realDiscNumber = meta.discNumber
                            if (meta.year != null) realYear = meta.year
                            if (meta.durationMs != null && meta.durationMs > 0L) realDuration = meta.durationMs
                            if (meta.bitrate != null && meta.bitrate > 0) realBitrate = meta.bitrate
                            if (meta.sampleRate != null && meta.sampleRate > 0) realSampleRate = meta.sampleRate
                        }
                        resolvedAlbumArtUri = tSong.resolveAlbumArtUri()
                    } catch (e: Exception) {
                        // Ignore read errors, fall back to TdApi metadata
                    }
                }
                
                // 3. Multi-Artist Processing
                val rawArtistName = if (realArtistName.isBlank()) "Unknown Artist" else realArtistName
                val splitArtists = rawArtistName.splitArtistsByDelimiters(delimiters, wordDelims)
                
                // Process Primary Artist (First in list)
                val primaryArtistName = splitArtists.firstOrNull()?.trim() ?: "Unknown Artist"
                
                var primaryArtistId = -1L
                
                splitArtists.forEachIndexed { index, individualArtistName ->
                    val cleanName = individualArtistName.trim()
                    val lowerName = cleanName.lowercase()
                    
                    // Check if artist exists locally (Merge logic)
                    val existingId = existingArtists[lowerName]
                    
                    val finalArtistId = if (existingId != null) {
                        existingId // Use Positive MediaStore ID
                    } else {
                        // Generate consistent negative ID for Telegram-only artist
                        val synthId = -(cleanName.hashCode().toLong().absoluteValue)
                        if (synthId == 0L) -1L else synthId
                    }

                    if (index == 0) primaryArtistId = finalArtistId

                    // Add to Artist Insert Map
                    if (!artistsToInsert.containsKey(finalArtistId)) {
                        artistsToInsert[finalArtistId] = ArtistEntity(
                            id = finalArtistId,
                            name = cleanName,
                            trackCount = 0, // Will be recalculated by Room or logic
                            imageUrl = existingArtistImageUrls[finalArtistId] // Keep existing image if merging
                        )
                    }

                    // Add Cross Ref
                    crossRefsToInsert.add(SongArtistCrossRef(
                        songId = finalSongId,
                        artistId = finalArtistId,
                        isPrimary = (index == 0)
                    ))
                }

                // 4. Album Logic
                // Try to match existing album by Name + Album Artist
                val albumKey = "${realAlbumName.trim().lowercase()}_${realAlbumArtist.trim().lowercase()}"
                val existingAlbumId = existingAlbums[albumKey]
                
                val finalAlbumId = if (existingAlbumId != null) {
                    existingAlbumId // Merge with local album
                } else {
                    // Synthetic negative ID
                    val synthId = -(realAlbumName.hashCode().toLong().absoluteValue)
                    if (synthId == 0L) -1L else synthId
                }
                
                if (!albumsToInsert.containsKey(finalAlbumId)) {
                     albumsToInsert[finalAlbumId] = AlbumEntity(
                        id = finalAlbumId,
                        title = realAlbumName,
                        artistName = realAlbumArtist, 
                        artistId = primaryArtistId, // Link to primary song artist (or album artist if we resolved it properly)
                        songCount = 0,
                        dateAdded = realDateAdded,
                        year = realYear,
                        albumArtUriString = resolvedAlbumArtUri
                    )
                }

                // 5. Build Final Song Entity
                // Build artists JSON from the split artists and their resolved IDs
                val telegramArtistRefs = splitArtists.mapIndexed { idx, name ->
                    val cleanName = name.trim()
                    val lowerName = cleanName.lowercase()
                    val artId = existingArtists[lowerName]
                        ?: artistsToInsert.values.find { it.name.equals(cleanName, ignoreCase = true) }?.id
                        ?: 0L
                    ArtistRef(id = artId, name = cleanName, isPrimary = idx == 0)
                }.filter { it.name.isNotEmpty() }

                val songEntity = SongEntity(
                    id = finalSongId,
                    title = realTitle,
                    artistName = rawArtistName, // Store full string for display
                    artistId = primaryArtistId,
                    albumName = realAlbumName,
                    albumId = finalAlbumId,
                    albumArtist = realAlbumArtist,
                    duration = realDuration,
                    contentUriString = "telegram://${tSong.chatId}/${tSong.messageId}",
                    albumArtUriString = resolvedAlbumArtUri,
                    filePath = tSong.filePath,
                    parentDirectoryPath = File(tSong.filePath).parent ?: "/Telegram/$channelName",
                    dateAdded = tSong.dateAdded,
                    genre = realGenre,
                    trackNumber = realTrackNumber,
                    discNumber = realDiscNumber,
                    year = realYear,
                    isFavorite = false,
                    lyrics = realLyrics,
                    mimeType = tSong.mimeType,
                    bitrate = realBitrate,
                    sampleRate = realSampleRate,
                    telegramChatId = tSong.chatId,
                    telegramFileId = tSong.fileId,
                    artistsJson = serializeArtistRefs(telegramArtistRefs),
                    sourceType = SourceType.TELEGRAM
                )
                songsToInsert.add(songEntity)
            }
            
            // Calculate song counts for the albums we are inserting
            val albumCounts = songsToInsert.groupingBy { it.albumId }.eachCount()

            val finalAlbums = albumsToInsert.values.map { album ->
                album.copy(songCount = albumCounts[album.id] ?: 0)
            }
            val syncedTelegramSongIds = songsToInsert.map { it.id }.toHashSet()
            val deletedUnifiedSongIds = existingUnifiedTelegramIds.filterNot { it in syncedTelegramSongIds }

            // Upsert into MusicDao
            musicDao.incrementalSyncMusicData(
                songs = songsToInsert,
                albums = finalAlbums,
                artists = artistsToInsert.values.toList(),
                crossRefs = crossRefsToInsert,
                deletedSongIds = deletedUnifiedSongIds
            )
            Log.i(TAG, "Synced ${songsToInsert.size} Telegram songs with Unified Metadata.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Telegram data", e)
        }
    }

    private suspend fun syncNeteaseData() {
        Log.i(TAG, "Syncing Netease songs to main database (Unified Mode)...")
        try {
            val neteaseSongs = neteaseDao.getAllNeteaseSongsList()
            val existingUnifiedNeteaseIds = musicDao.getAllNeteaseSongIds()

            if (neteaseSongs.isEmpty()) {
                if (existingUnifiedNeteaseIds.isNotEmpty()) {
                    musicDao.clearAllNeteaseSongs()
                }
                Log.d(TAG, "No Netease songs to sync.")
                return
            }

            val songsToInsert = ArrayList<SongEntity>(neteaseSongs.size)
            val artistsToInsert = LinkedHashMap<Long, ArtistEntity>()
            val albumsToInsert = LinkedHashMap<Long, AlbumEntity>()
            val crossRefsToInsert = mutableListOf<SongArtistCrossRef>()

            neteaseSongs.forEach { nSong ->
                val songId = toUnifiedNeteaseSongId(nSong.neteaseId)
                val artistNames = parseNeteaseArtistNames(nSong.artist)
                val primaryArtistName = artistNames.firstOrNull() ?: "Unknown Artist"
                val primaryArtistId = toUnifiedNeteaseArtistId(primaryArtistName)

                artistNames.forEachIndexed { index, artistName ->
                    val artistId = toUnifiedNeteaseArtistId(artistName)
                    artistsToInsert.putIfAbsent(
                        artistId,
                        ArtistEntity(
                            id = artistId,
                            name = artistName,
                            trackCount = 0,
                            imageUrl = null
                        )
                    )
                    crossRefsToInsert.add(
                        SongArtistCrossRef(
                            songId = songId,
                            artistId = artistId,
                            isPrimary = index == 0
                        )
                    )
                }

                val albumId = toUnifiedNeteaseAlbumId(nSong.albumId, nSong.album)
                val albumName = nSong.album.ifBlank { "Unknown Album" }
                albumsToInsert.putIfAbsent(
                    albumId,
                    AlbumEntity(
                        id = albumId,
                        title = albumName,
                        artistName = primaryArtistName,
                        artistId = primaryArtistId,
                        songCount = 0,
                        dateAdded = nSong.dateAdded,
                        year = 0,
                        albumArtUriString = nSong.albumArtUrl
                    )
                )

                // Build artists JSON
                val neteaseArtistRefs = artistNames.mapIndexed { idx, name ->
                    ArtistRef(
                        id = toUnifiedNeteaseArtistId(name),
                        name = name,
                        isPrimary = idx == 0
                    )
                }

                songsToInsert.add(
                    SongEntity(
                        id = songId,
                        title = nSong.title,
                        artistName = nSong.artist.ifBlank { primaryArtistName },
                        artistId = primaryArtistId,
                        albumArtist = null,
                        albumName = albumName,
                        albumId = albumId,
                        contentUriString = "netease://${nSong.neteaseId}",
                        albumArtUriString = nSong.albumArtUrl,
                        duration = nSong.duration,
                        genre = NETEASE_GENRE,
                        filePath = "",
                        parentDirectoryPath = NETEASE_PARENT_DIRECTORY,
                        isFavorite = false,
                        lyrics = null,
                        trackNumber = 0,
                        year = 0,
                        dateAdded = nSong.dateAdded.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        mimeType = nSong.mimeType,
                        bitrate = nSong.bitrate,
                        sampleRate = null,
                        telegramChatId = null,
                        telegramFileId = null,
                        artistsJson = serializeArtistRefs(neteaseArtistRefs),
                        sourceType = SourceType.NETEASE
                    )
                )
            }

            val albumCounts = songsToInsert.groupingBy { it.albumId }.eachCount()
            val finalAlbums = albumsToInsert.values.map { album ->
                album.copy(songCount = albumCounts[album.id] ?: 0)
            }

            val currentUnifiedSongIds = songsToInsert.map { it.id }.toSet()
            val deletedUnifiedSongIds = existingUnifiedNeteaseIds.filter { it !in currentUnifiedSongIds }

            musicDao.incrementalSyncMusicData(
                songs = songsToInsert,
                albums = finalAlbums,
                artists = artistsToInsert.values.toList(),
                crossRefs = crossRefsToInsert,
                deletedSongIds = deletedUnifiedSongIds
            )
            Log.i(TAG, "Synced ${songsToInsert.size} Netease songs with Unified Metadata.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Netease data", e)
        }
    }

    private fun parseNeteaseArtistNames(rawArtist: String): List<String> {
        if (rawArtist.isBlank()) return listOf("Unknown Artist")
        val parsed = rawArtist.split(Regex("\\s*[,/&;+、]\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (parsed.isEmpty()) listOf("Unknown Artist") else parsed
    }

    private fun toUnifiedNeteaseSongId(neteaseId: Long): Long {
        return -(NETEASE_SONG_ID_OFFSET + neteaseId.absoluteValue)
    }

    private fun toUnifiedNeteaseAlbumId(albumId: Long, albumName: String): Long {
        val normalized = if (albumId > 0L) {
            albumId.absoluteValue
        } else {
            albumName.lowercase().hashCode().toLong().absoluteValue
        }
        return -(NETEASE_ALBUM_ID_OFFSET + normalized)
    }

    private fun toUnifiedNeteaseArtistId(artistName: String): Long {
        return -(NETEASE_ARTIST_ID_OFFSET + artistName.lowercase().hashCode().toLong().absoluteValue)
    }

    private suspend fun syncNavidromeData() {
        if (!navidromeRepository.isLoggedIn) return

        val lastSync = navidromeRepository.lastFullSyncTime
        val currentTime = System.currentTimeMillis()

        // Only auto-sync Navidrome during main library sync if it's been more than SYNC_THRESHOLD_MS (24h)
        if (currentTime - lastSync < NavidromeRepository.SYNC_THRESHOLD_MS) {
            Log.d(TAG, "Skipping Navidrome sync during main library sync - last sync was recent.")
            // Still sync unified library from local cache to be safe
            navidromeRepository.syncUnifiedLibrarySongsFromNavidrome()
            return
        }

        Log.i(TAG, "Syncing Navidrome data from server...")
        try {
            // Fetch playlists and songs from the Navidrome server, then sync to unified library
            val result = navidromeRepository.syncAllPlaylistsAndSongs()
            result.fold(
                onSuccess = { summary ->
                    Log.i(TAG, "Navidrome sync complete: ${summary.playlistCount} playlists, ${summary.syncedSongCount} songs synced (${summary.failedPlaylistCount} failed)")
                },
                onFailure = { e ->
                    Log.w(TAG, "Navidrome server sync failed, falling back to local cache sync", e)
                    // Fallback: at least sync what we already have cached
                    navidromeRepository.syncUnifiedLibrarySongsFromNavidrome()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Navidrome data", e)
        }
    }
}
