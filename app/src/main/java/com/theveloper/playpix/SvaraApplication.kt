package com.svara.music

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.svara.music.data.preferences.UserPreferencesRepository
import com.svara.music.data.repository.ArtistImageRepository
import com.svara.music.data.streaming.StreamingRepository
import com.svara.music.data.telegram.TelegramRepository
import com.svara.music.presentation.viewmodel.LibraryStateHolder
import com.svara.music.presentation.viewmodel.ThemeStateHolder
import com.svara.music.utils.AlbumArtCacheManager
import com.svara.music.utils.AlbumArtUtils
import com.svara.music.utils.CrashHandler
import com.svara.music.utils.AppLocaleManager
import com.svara.music.utils.MediaMetadataRetrieverPool
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class SvaraApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    @Inject
    lateinit var telegramCoilFetcherFactory: dagger.Lazy<com.svara.music.data.image.TelegramCoilFetcher.Factory>

    @Inject
    lateinit var navidromeCoilFetcherFactory: dagger.Lazy<com.svara.music.data.image.NavidromeCoilFetcher.Factory>

    @Inject
    lateinit var jellyfinCoilFetcherFactory: dagger.Lazy<com.svara.music.data.image.JellyfinCoilFetcher.Factory>

    @Inject
    lateinit var localArtworkCoilFetcherFactory: dagger.Lazy<com.svara.music.data.image.LocalArtworkCoilFetcher.Factory>

    @Inject
    lateinit var themeStateHolder: dagger.Lazy<ThemeStateHolder>

    @Inject
    lateinit var artistImageRepository: dagger.Lazy<ArtistImageRepository>

    @Inject
    lateinit var telegramRepository: dagger.Lazy<TelegramRepository>

    @Inject
    lateinit var libraryStateHolder: dagger.Lazy<LibraryStateHolder>

    @Inject
    lateinit var userPreferencesRepository: dagger.Lazy<UserPreferencesRepository>

    @Inject
    lateinit var streamingRepository: dagger.Lazy<StreamingRepository>

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // AÑADE EL COMPANION OBJECT
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "svara_music_channel"
    }

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            libraryStateHolder.get().restoreAfterTrimIfNeeded()
        }
    }

    override fun attachBaseContext(base: Context) {
        // Install crash handler as early as possible — BEFORE Hilt injection (super.onCreate).
        // This ensures we capture crashes that happen during DI graph construction.
        if (BuildConfig.BUILD_TYPE != "benchmark") {
            CrashHandler.install(base)
        }
        super.attachBaseContext(AppLocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()

        // CrashHandler already installed in attachBaseContext — no need to re-install.
        // (Legacy guard kept for benchmark variant awareness only.)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release tree: only WARN/ERROR/WTF - no DEBUG/VERBOSE/INFO
            Timber.plant(ReleaseTree())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Svara Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        startupScope.launch {
            AlbumArtUtils.migrateLegacyCacheLocation(this@SvaraApplication)
            val savedLimit = runCatching {
                userPreferencesRepository.get().albumArtCacheLimitMbFlow.first()
            }.getOrNull()
            if (savedLimit != null) {
                AlbumArtCacheManager.configuredCacheLimitMb = savedLimit.toLong()
            }
        }

        // Pre-fetch trending streaming songs into DB so Library/Home shows them immediately.
        startupScope.launch {
            runCatching { streamingRepository.get().getTrendingSongs(limit = 50) }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoader.get().newBuilder()
            .components {
                add(localArtworkCoilFetcherFactory.get())
                add(telegramCoilFetcherFactory.get())
                add(navidromeCoilFetcherFactory.get())
                add(jellyfinCoilFetcherFactory.get())
            }
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        imageLoader.get().memoryCache?.trimMemory(level)

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            themeStateHolder.get().trimMemory(level)
        }

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            artistImageRepository.get().clearCache()
            telegramRepository.get().clearMemoryCache()
            MediaMetadataRetrieverPool.clear()
        }

        libraryStateHolder.get().trimMemory(level)

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            imageLoader.get().memoryCache?.clear()
        }
    }

    // 3. Sobrescribe el método para proveer la configuración de WorkManager
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
