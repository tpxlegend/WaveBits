package com.music.spotui.ui.components

import java.util.Calendar

/**
 * Returns dynamic greeting based on current local hour:
 * - 05:00 - 11:59 -> "Good morning"
 * - 12:00 - 16:59 -> "Good afternoon"
 * - 17:00 - 21:59 -> "Good evening"
 * - 22:00 - 04:59 -> "Good night"
 */
fun getTimeGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
}
