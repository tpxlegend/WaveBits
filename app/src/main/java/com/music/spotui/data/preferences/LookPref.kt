package com.music.spotui.data.preferences

import android.content.Context

/** The app's look mode (always [IMMERSIVE] — Classic look has been removed). */
enum class Look {
    IMMERSIVE;

    companion object {
        fun of(name: String?): Look = IMMERSIVE
    }
}

object LookPref {
    val look: Look = Look.IMMERSIVE

    fun init(c: Context) {
        // Always Immersive
    }

    fun set(c: Context, value: Look) {
        // Always Immersive
    }

    fun isChosen(c: Context): Boolean = true

    fun setChosen(c: Context) {
        // No-op
    }

    fun isOffered(c: Context): Boolean = true

    fun setOffered(c: Context) {
        // No-op
    }

    fun clearOffered(c: Context) {
        // No-op
    }
}
