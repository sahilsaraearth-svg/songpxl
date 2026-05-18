package com.svara.music.di

import com.svara.music.data.itunes.ItunesApiService
import com.svara.music.data.jiosaavn.JioSaavnApiService
import com.svara.music.data.soundcloud.SoundCloudApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class JioSaavnRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundCloudRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ItunesRetrofit

@Module
@InstallIn(SingletonComponent::class)
object StreamingModule {

    @Provides
    @Singleton
    @JioSaavnRetrofit
    fun provideJioSaavnRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://saavn.dev/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideJioSaavnApiService(@JioSaavnRetrofit retrofit: Retrofit): JioSaavnApiService {
        return retrofit.create(JioSaavnApiService::class.java)
    }

    @Provides
    @Singleton
    @SoundCloudRetrofit
    fun provideSoundCloudRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api-v2.soundcloud.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideSoundCloudApiService(@SoundCloudRetrofit retrofit: Retrofit): SoundCloudApiService {
        return retrofit.create(SoundCloudApiService::class.java)
    }

    @Provides
    @Singleton
    @ItunesRetrofit
    fun provideItunesRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideItunesApiService(@ItunesRetrofit retrofit: Retrofit): ItunesApiService {
        return retrofit.create(ItunesApiService::class.java)
    }
}
