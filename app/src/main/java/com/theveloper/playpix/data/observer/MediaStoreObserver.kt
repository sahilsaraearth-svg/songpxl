package com.svara.music.data.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStoreObserver — kept for Hilt injection compatibility but local MediaStore
 * scanning has been removed. The flow emits once on startup so downstream collectors
 * (getAudioFiles, etc.) load from the DB exactly once.
 */
@Singleton
class MediaStoreObserver @Inject constructor(
    @ApplicationContext private val context: Context
) : ContentObserver(Handler(Looper.getMainLooper())), DefaultLifecycleObserver {

    private val _mediaStoreChanges = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val mediaStoreChanges: SharedFlow<Unit> = _mediaStoreChanges.asSharedFlow()

    init {
        // Emit once so collectors immediately get a value without waiting for MediaStore events.
        _mediaStoreChanges.tryEmit(Unit)
    }

    /** No-op: local MediaStore scanning is disabled. */
    fun register() { /* no-op */ }

    /** No-op: local MediaStore scanning is disabled. */
    fun unregister() { /* no-op */ }

    override fun onStart(owner: LifecycleOwner) { /* no-op */ }
    override fun onStop(owner: LifecycleOwner) { /* no-op */ }
    override fun onDestroy(owner: LifecycleOwner) { /* no-op */ }

    override fun onChange(selfChange: Boolean, uri: Uri?) { /* no-op */ }

    fun forceRescan() { /* no-op */ }
}
