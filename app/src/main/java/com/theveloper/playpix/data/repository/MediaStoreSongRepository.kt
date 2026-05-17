package com.svara.music.data.repository

import android.content.Context
import androidx.paging.PagingData
import androidx.paging.map
import com.svara.music.data.database.FavoritesDao
import com.svara.music.data.database.MusicDao
import com.svara.music.data.database.toSong
import com.svara.music.data.model.Song
import com.svara.music.data.model.SortOption
import com.svara.music.data.model.StorageFilter
import com.svara.music.data.observer.MediaStoreObserver
import com.svara.music.data.preferences.UserPreferencesRepository
import com.svara.music.utils.DirectoryFilterUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStoreSongRepository — local MediaStore scanning is disabled.
 * All methods return empty results or delegate to the Room DB (which is
 * populated exclusively by the streaming / cloud sync layers).
 * Kept for Hilt injection-graph compatibility.
 */
@Singleton
class MediaStoreSongRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreObserver: MediaStoreObserver,
    private val favoritesDao: FavoritesDao,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository
) : SongRepository {

    private fun normalizePath(path: String): String = File(path).absolutePath

    private suspend fun computeAllowedDirs(
        allowedDirs: Set<String>,
        blockedDirs: Set<String>
    ): Pair<List<String>, Boolean> {
        return DirectoryFilterUtils.computeAllowedParentDirs(
            allowedDirs = allowedDirs,
            blockedDirs = blockedDirs,
            getAllParentDirs = { musicDao.getDistinctParentDirectories() },
            normalizePath = ::normalizePath
        )
    }

    private val defaultPagingConfig = androidx.paging.PagingConfig(
        pageSize = 50,
        enablePlaceholders = true,
        maxSize = 250
    )

    // getSongs() — DB-only (streaming/cloud songs stored in DB by JioSaavnRepository)
    override fun getSongs(): Flow<List<Song>> = flowOf(emptyList())

    override fun getSongsByAlbum(albumId: Long): Flow<List<Song>> = flowOf(emptyList())

    override fun getSongsByArtist(artistId: Long): Flow<List<Song>> = flowOf(emptyList())

    /** Search is handled entirely by MusicRepositoryImpl via streaming API + DB. */
    override suspend fun searchSongs(query: String): List<Song> = emptyList()

    override fun getSongById(songId: Long): Flow<Song?> = flowOf(null)

    /** No-arg getPaginatedSongs — never called; MediaStorePagingSource removed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedSongs(): Flow<PagingData<Song>> = flowOf(PagingData.empty())

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedSongs(
        sortOption: SortOption,
        storageFilter: StorageFilter
    ): Flow<PagingData<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    androidx.paging.Pager(
                        config = defaultPagingConfig,
                        pagingSourceFactory = {
                            musicDao.getSongsPaginated(
                                allowedParentDirs = allowedParentDirs,
                                applyDirectoryFilter = applyDirectoryFilter,
                                sortOrder = sortOption.storageKey,
                                filterMode = storageFilter.value
                            )
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity -> entity.toSong() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedFavoriteSongs(
        sortOption: SortOption,
        storageFilter: StorageFilter
    ): Flow<PagingData<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    androidx.paging.Pager(
                        config = defaultPagingConfig,
                        pagingSourceFactory = {
                            musicDao.getFavoriteSongsPaginated(
                                allowedParentDirs = allowedParentDirs,
                                applyDirectoryFilter = applyDirectoryFilter,
                                sortOrder = sortOption.storageKey,
                                filterMode = storageFilter.value
                            )
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity -> entity.toSong().copy(isFavorite = true) }
        }
    }

    override suspend fun getFavoriteSongsOnce(
        storageFilter: StorageFilter
    ): List<Song> = withContext(Dispatchers.IO) {
        val allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first()
        val blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first()
        val (allowedParentDirs, applyDirectoryFilter) =
            computeAllowedDirs(allowedDirs, blockedDirs)
        musicDao.getFavoriteSongsList(
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter,
            filterMode = storageFilter.value
        ).map { entity -> entity.toSong().copy(isFavorite = true) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getFavoriteSongCountFlow(
        storageFilter: StorageFilter
    ): Flow<Int> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(Pair(allowedParentDirs, applyDirectoryFilter))
            }.flatMapLatest { (allowedDirs, applyFilter) ->
                musicDao.getFavoriteSongCount(
                    allowedParentDirs = allowedDirs,
                    applyDirectoryFilter = applyFilter,
                    filterMode = storageFilter.value
                )
            }
        }
    }
}
