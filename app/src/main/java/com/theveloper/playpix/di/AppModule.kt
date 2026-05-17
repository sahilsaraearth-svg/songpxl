package com.theveloper.playpix.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.theveloper.playpix.BuildConfig
import com.theveloper.playpix.PlayPixApplication
import com.theveloper.playpix.data.database.AlbumArtThemeDao
import com.theveloper.playpix.data.database.EngagementDao
import com.theveloper.playpix.data.database.FavoritesDao
import com.theveloper.playpix.data.database.GDriveDao
import com.theveloper.playpix.data.database.LyricsDao
import com.theveloper.playpix.data.database.AiCacheDao
import com.theveloper.playpix.data.database.AiUsageDao
import com.theveloper.playpix.data.database.LocalPlaylistDao
import com.theveloper.playpix.data.database.MusicDao
import com.theveloper.playpix.data.database.PlayPixDatabase
import com.theveloper.playpix.data.database.SearchHistoryDao
import com.theveloper.playpix.data.database.TransitionDao
import com.theveloper.playpix.data.preferences.UserPreferencesRepository
import com.theveloper.playpix.data.preferences.PlaylistPreferencesRepository
import com.theveloper.playpix.data.preferences.dataStore
import com.theveloper.playpix.data.media.SongMetadataEditor
import com.theveloper.playpix.data.network.deezer.DeezerApiService
import com.theveloper.playpix.data.network.netease.NeteaseApiService
import com.theveloper.playpix.data.network.lyrics.LrcLibApiService
import com.theveloper.playpix.data.repository.ArtistImageRepository
import com.theveloper.playpix.data.repository.LyricsRepository
import com.theveloper.playpix.data.repository.LyricsRepositoryImpl
import com.theveloper.playpix.data.repository.MediaStoreSongRepository
import com.theveloper.playpix.data.repository.MusicRepository
import com.theveloper.playpix.data.repository.MusicRepositoryImpl
import com.theveloper.playpix.data.repository.SongRepository
import com.theveloper.playpix.data.repository.TransitionRepository
import com.theveloper.playpix.data.repository.TransitionRepositoryImpl
import com.theveloper.playpix.data.repository.FolderTreeBuilder
import dagger.Module
import dagger.Provides
import dagger.Lazy
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideApplication(@ApplicationContext app: Context): PlayPixApplication {
        return app as PlayPixApplication
    }

    @Singleton
    @Provides
    fun provideGson(): com.google.gson.Gson {
        return com.google.gson.Gson()
    }

    @OptIn(UnstableApi::class)
    @Singleton
    @Provides
    fun provideSessionToken(@ApplicationContext context: Context): androidx.media3.session.SessionToken {
        return androidx.media3.session.SessionToken(
            context,
            android.content.ComponentName(context, com.theveloper.playpix.data.service.MusicService::class.java)
        )
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.dataStore

    @Singleton
    @Provides
    fun provideJson(): Json { // Proveer Json
        return Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    @Singleton
    @Provides
    @AppScope
    fun provideAppCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Singleton
    @Provides
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Singleton
    @Provides
    fun providePlayPixDatabase(@ApplicationContext context: Context): PlayPixDatabase {
        val builder = Room.databaseBuilder(
            context.applicationContext,
            PlayPixDatabase::class.java,
            "playpix_database"
        ).addMigrations(
            PlayPixDatabase.MIGRATION_3_4,
            PlayPixDatabase.MIGRATION_4_5,
            PlayPixDatabase.MIGRATION_5_6,
            PlayPixDatabase.MIGRATION_6_7,
            PlayPixDatabase.MIGRATION_7_8,
            PlayPixDatabase.MIGRATION_8_9,
            PlayPixDatabase.MIGRATION_9_10,
            PlayPixDatabase.MIGRATION_10_11,
            PlayPixDatabase.MIGRATION_11_12,
            PlayPixDatabase.MIGRATION_12_13,
            PlayPixDatabase.MIGRATION_13_14,
            PlayPixDatabase.MIGRATION_14_15,
            PlayPixDatabase.MIGRATION_15_16,
            PlayPixDatabase.MIGRATION_16_17,
            PlayPixDatabase.MIGRATION_17_18,
            PlayPixDatabase.MIGRATION_18_19,
            PlayPixDatabase.MIGRATION_19_20,
            PlayPixDatabase.MIGRATION_20_21,
            PlayPixDatabase.MIGRATION_21_22,
            PlayPixDatabase.MIGRATION_22_23,
            PlayPixDatabase.MIGRATION_23_24,
            PlayPixDatabase.MIGRATION_24_25,
            PlayPixDatabase.MIGRATION_25_26,
            PlayPixDatabase.MIGRATION_26_27,
            PlayPixDatabase.MIGRATION_27_28,
            PlayPixDatabase.MIGRATION_28_29,
            PlayPixDatabase.MIGRATION_29_30,
            PlayPixDatabase.MIGRATION_30_31,
            PlayPixDatabase.MIGRATION_31_32,
            PlayPixDatabase.MIGRATION_32_33,
            PlayPixDatabase.MIGRATION_33_34,
            PlayPixDatabase.MIGRATION_34_35,
            PlayPixDatabase.MIGRATION_35_36,
            PlayPixDatabase.MIGRATION_36_37,
            PlayPixDatabase.MIGRATION_37_38,
            PlayPixDatabase.MIGRATION_38_39,
            PlayPixDatabase.MIGRATION_39_40,
            PlayPixDatabase.MIGRATION_40_41
        )
            .addCallback(PlayPixDatabase.createRuntimeArtifactsCallback())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

        // Allow destructive migration in all builds.
        // Streaming-only mode — no local songs in DB. Only playlists/favorites/stats
        // are stored locally, but a crash on launch is worse than losing those.
        // If migration fails, wipe and rebuild rather than crash.
        builder.fallbackToDestructiveMigration(dropAllTables = true)

        return builder.build()
    }

    @Singleton
    @Provides
    fun provideAlbumArtThemeDao(database: PlayPixDatabase): AlbumArtThemeDao {
        return database.albumArtThemeDao()
    }

    @Singleton
    @Provides
    fun provideSearchHistoryDao(database: PlayPixDatabase): SearchHistoryDao { // NUEVO MÉTODO
        return database.searchHistoryDao()
    }

    @Singleton
    @Provides
    fun provideMusicDao(database: PlayPixDatabase): MusicDao { // Proveer MusicDao
        return database.musicDao()
    }

    @Singleton
    @Provides
    fun provideTransitionDao(database: PlayPixDatabase): TransitionDao {
        return database.transitionDao()
    }

    @Singleton
    @Provides
    fun provideEngagementDao(database: PlayPixDatabase): EngagementDao {
        return database.engagementDao()
    }

    @Singleton
    @Provides
    fun provideFavoritesDao(database: PlayPixDatabase): FavoritesDao {
        return database.favoritesDao()
    }

    @Singleton
    @Provides
    fun provideLyricsDao(database: PlayPixDatabase): LyricsDao {
        return database.lyricsDao()
    }

    @Singleton
    @Provides
    fun provideGDriveDao(database: PlayPixDatabase): GDriveDao {
        return database.gdriveDao()
    }

    @Singleton
    @Provides
    fun provideLocalPlaylistDao(database: PlayPixDatabase): LocalPlaylistDao {
        return database.localPlaylistDao()
    }

    @Singleton
    @Provides
    fun provideQqMusicDao(database: PlayPixDatabase): com.theveloper.playpix.data.database.QqMusicDao {
        return database.qqmusicDao()
    }

    @Singleton
    @Provides
    fun provideNavidromeDao(database: PlayPixDatabase): com.theveloper.playpix.data.database.NavidromeDao {
        return database.navidromeDao()
    }
    
    @Singleton
    @Provides
    fun provideAiCacheDao(database: PlayPixDatabase): AiCacheDao {
        return database.aiCacheDao()
    }

    @Provides
    fun provideAiUsageDao(database: PlayPixDatabase): AiUsageDao {
        return database.aiUsageDao()
    }

    @Singleton
    @Provides
    fun provideJellyfinDao(database: PlayPixDatabase): com.theveloper.playpix.data.database.JellyfinDao {
        return database.jellyfinDao()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        // Add interceptor for QQ Music images
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()

                // Add Referer header for QQ Music images
                val newRequest = if (url.contains("y.qq.com")) {
                    request.newBuilder()
                        .header("Referer", "https://y.qq.com/")
                        .build()
                } else {
                    request
                }

                chain.proceed(newRequest)
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .dispatcher(Dispatchers.Default) // Use CPU-bound dispatcher for decoding
            .allowHardware(true) // Re-enable hardware bitmaps for better performance
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.20) // Use 20% of app memory for image cache
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB disk cache
                    .build()
            }
            .respectCacheHeaders(false) // Ignore server cache headers, always cache
            .build()
    }

    @Provides
    @Singleton
    fun provideLyricsRepository(
        @ApplicationContext context: Context,
        lrcLibApiService: LrcLibApiService,
        lyricsDao: LyricsDao,
        okHttpClient: OkHttpClient
    ): LyricsRepository {
        return LyricsRepositoryImpl(
            context = context,
            lrcLibApiService = lrcLibApiService,
            lyricsDao = lyricsDao,
            okHttpClient = okHttpClient
        )
    }

    @Provides
    @Singleton
    fun provideSongRepository(
        @ApplicationContext context: Context,
        mediaStoreObserver: com.theveloper.playpix.data.observer.MediaStoreObserver,
        favoritesDao: FavoritesDao,
        userPreferencesRepository: UserPreferencesRepository,
        musicDao: MusicDao
    ): SongRepository {
        return MediaStoreSongRepository(
            context = context,
            mediaStoreObserver = mediaStoreObserver,
            favoritesDao = favoritesDao,
            userPreferencesRepository = userPreferencesRepository,
            musicDao = musicDao
        )
    }

    @Singleton
    @Provides
    fun provideTelegramDao(database: PlayPixDatabase): com.theveloper.playpix.data.database.TelegramDao {
        return database.telegramDao()
    }

    @Singleton
    @Provides
    fun provideNeteaseDao(database: PlayPixDatabase): com.theveloper.playpix.data.database.NeteaseDao {
        return database.neteaseDao()
    }

    @Provides
    @Singleton
    fun provideFolderTreeBuilder(): FolderTreeBuilder {
        return FolderTreeBuilder()
    }

    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context,
        userPreferencesRepository: UserPreferencesRepository,
        playlistPreferencesRepository: PlaylistPreferencesRepository,
        searchHistoryDao: SearchHistoryDao,
        musicDao: MusicDao,
        lyricsRepository: LyricsRepository,
        telegramDao: com.theveloper.playpix.data.database.TelegramDao,
        telegramCacheManager: Lazy<com.theveloper.playpix.data.telegram.TelegramCacheManager>,
        telegramRepository: Lazy<com.theveloper.playpix.data.telegram.TelegramRepository>,
        songRepository: SongRepository,
        favoritesDao: FavoritesDao,
        artistImageRepository: ArtistImageRepository,
        folderTreeBuilder: FolderTreeBuilder,
        streamingRepository: com.theveloper.playpix.data.streaming.StreamingRepository
    ): MusicRepository {
        return MusicRepositoryImpl(
            context = context,
            userPreferencesRepository = userPreferencesRepository,
            playlistPreferencesRepository = playlistPreferencesRepository,
            searchHistoryDao = searchHistoryDao,
            musicDao = musicDao,
            lyricsRepository = lyricsRepository,
            telegramDao = telegramDao,
            telegramCacheManagerProvider = telegramCacheManager,
            telegramRepositoryProvider = telegramRepository,
            songRepository = songRepository,
            favoritesDao = favoritesDao,
            artistImageRepository = artistImageRepository,
            folderTreeBuilder = folderTreeBuilder,
            streamingRepository = streamingRepository
        )
    }

    @Provides
    @Singleton
    fun provideTransitionRepository(
        transitionRepositoryImpl: TransitionRepositoryImpl
    ): TransitionRepository {
        return transitionRepositoryImpl
    }

    @Singleton
    @Provides
    fun provideSongMetadataEditor(
        @ApplicationContext context: Context,
        musicDao: MusicDao,
        telegramDao: com.theveloper.playpix.data.database.TelegramDao,
        userPreferencesRepository: UserPreferencesRepository
    ): SongMetadataEditor {
        return SongMetadataEditor(context, musicDao, telegramDao, userPreferencesRepository)
    }

    /**
     * Provee una instancia singleton de OkHttpClient con logging e interceptor de User-Agent.
     * Retry logic with backoff is handled in coroutine-based callers.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.setLevel(
            if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        )
        
        // Connection pool with optimized connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Add User-Agent header (required by some APIs)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", "PlayPix/1.0 (Android; Music Player)")
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provee una instancia de OkHttpClient con timeouts para búsquedas de lyrics.
     * Includes DNS resolver, modern TLS, connection pool, and connection retry.
     */
    @Provides
    @Singleton
    @FastOkHttpClient
    fun provideFastOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS)
        
        // Connection pool to reuse connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        // Use Cloudflare and Google DNS to avoid potential DNS issues
        val dns = okhttp3.Dns { hostname ->
            try {
                // First try system DNS
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                // Fallback to manual resolution if system DNS fails
                java.net.InetAddress.getAllByName(hostname).toList()
            }
        }

        return OkHttpClient.Builder()
            .dns(dns)
            .connectionPool(connectionPool)
            // Use HTTP/1.1 to avoid HTTP/2 stream issues with some servers
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // Use modern TLS connection spec
            .connectionSpecs(listOf(
                okhttp3.ConnectionSpec.MODERN_TLS,
                okhttp3.ConnectionSpec.COMPATIBLE_TLS
            ))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            // Enable built-in retry on connection failure
            .retryOnConnectionFailure(true)
            // Add headers
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithHeaders = originalRequest.newBuilder()
                    .header("User-Agent", "PlayPix/1.0 (Android; Music Player)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(requestWithHeaders)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provee una instancia singleton de Retrofit para la API de LRCLIB.
     */
    @Provides
    @Singleton
    fun provideRetrofit(@FastOkHttpClient okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provee una instancia singleton del servicio de la API de LRCLIB.
     */
    @Provides
    @Singleton
    fun provideLrcLibApiService(retrofit: Retrofit): LrcLibApiService {
        return retrofit.create(LrcLibApiService::class.java)
    }

    /**
     * Provee una instancia de Retrofit para la API de Deezer.
     */
    @Provides
    @Singleton
    @DeezerRetrofit
    fun provideDeezerRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provee el servicio de la API de Deezer.
     */
    @Provides
    @Singleton
    fun provideDeezerApiService(@DeezerRetrofit retrofit: Retrofit): DeezerApiService {
        return retrofit.create(DeezerApiService::class.java)
    }

    /**
     * Provee el repositorio de imágenes de artistas.
     */
    @Provides
    @Singleton
    fun provideArtistImageRepository(
        deezerApiService: DeezerApiService,
        musicDao: MusicDao
    ): ArtistImageRepository {
        return ArtistImageRepository(deezerApiService, musicDao)
    }
}
