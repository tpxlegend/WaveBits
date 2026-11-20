package com.music.spotui.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

val AppBackground: Color get() =
    if (app.wavebits.power.EnergySettings.oledBlack) Color.Black else Color(0xFF121212)
val GridBackground: Color get() = tone(0xFF2A2A2A)

/** Green used specifically for the initial entrance page (WelcomeScreen). */
val SpotifyGreen: Color = Color(0xFF1ED760)

/** The accent (White 🤍 by default, or the colour a supporter chose). */
val AppPalette: Color get() = AppAccent
val AppAccent: Color get() = com.music.spotui.data.api.SupportReminder.accentColor

/** Primary text and icons. */
val AppText: Color = Color.White
/** Secondary text and icons. */
val AppMuted: Color = Color(0xFFB3B3B3)
/** What a tint is mixed with to make a header. */
val AppShade: Color = Color.Black

/** One of the palette's raised greys (#181818 ... #3E3E3E): "a bit raised from the page". */
fun tone(argb: Long): Color = Color(argb)

/** The cover's colour as a page header: darkened toward black, so the title stays readable. */
fun headerTint(tint: Color, amount: Float): Color = lerp(tint, Color.Black, amount)
