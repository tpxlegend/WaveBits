package com.music.spotui.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * Headphones unplugged or a Bluetooth headset gone: the music pauses instead of
 * moving to the phone's speaker, like every music app. Music playing on another
 * Connect device is left alone (this phone is only the remote).
 */
object NoisyAudio {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (SongPlayer.activeDevice.value == null && SongPlayer.isPlaying()) SongPlayer.pause(by = "headphones unplugged")
        }
    }

    fun register(context: Context) {
        runCatching {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
