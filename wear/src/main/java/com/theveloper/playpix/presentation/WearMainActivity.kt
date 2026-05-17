package com.svara.music.presentation

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.wear.ambient.AmbientLifecycleObserver
import com.svara.music.data.WearLifecycleState
import com.svara.music.presentation.theme.WearSvaraTheme
import com.svara.music.presentation.viewmodel.WearPlayerViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WearMainActivity : FragmentActivity() {

    companion object {
        val isForeground: Boolean
            get() = WearLifecycleState.isForeground.value
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            WearLifecycleState.setAmbient(true)
        }

        override fun onExitAmbient() {
            WearLifecycleState.setAmbient(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AmbientLifecycleObserver(this, ambientCallback).also {
            lifecycle.addObserver(it)
        }

        setContent {
            val playerViewModel: WearPlayerViewModel = hiltViewModel()
            val albumArt by playerViewModel.albumArt.collectAsState()
            val paletteSeedArgb by playerViewModel.paletteSeedArgb.collectAsState()
            val themePalette by playerViewModel.themePalette.collectAsState()

            WearSvaraTheme(
                albumArt = albumArt,
                seedColorArgb = paletteSeedArgb,
                themePalette = themePalette,
            ) {
                WearNavigation()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        WearLifecycleState.setForeground(true)
    }

    override fun onStop() {
        WearLifecycleState.setForeground(false)
        super.onStop()
    }
}
