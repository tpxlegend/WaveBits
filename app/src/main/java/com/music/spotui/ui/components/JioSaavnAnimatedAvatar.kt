package com.music.spotui.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class JioSaavnAvatarPreset(
    val id: String,
    val title: String,
    val category: String = "People",
)

val JIOSAAVN_AVATAR_PRESETS = listOf(
    JioSaavnAvatarPreset("beard_man", "Bearded Gent", "People"),
    JioSaavnAvatarPreset("glasses_woman", "Executive Lady", "People"),
    JioSaavnAvatarPreset("casual_guy", "Casual Guy", "People"),
    JioSaavnAvatarPreset("redhead", "Redhead Girl", "People"),
    JioSaavnAvatarPreset("business_man", "Business Pro", "People"),
    JioSaavnAvatarPreset("afro_woman", "Afro Style", "People"),
    JioSaavnAvatarPreset("bun_woman", "Classic Bun", "People"),
    JioSaavnAvatarPreset("cool_specs", "Cool Specs", "People"),
    JioSaavnAvatarPreset("executive", "Senior Partner", "People"),
    JioSaavnAvatarPreset("braid_woman", "Side Braid", "People"),
)

fun isJioSaavnAvatarId(id: String?): Boolean {
    if (id.isNullOrBlank()) return false
    if (id.startsWith("http://") || id.startsWith("https://") || id.startsWith("content://") || id.startsWith("file://")) {
        return false
    }
    return JIOSAAVN_AVATAR_PRESETS.any { it.id.equals(id, ignoreCase = true) } || true
}

@Composable
fun JioSaavnAnimatedAvatar(
    avatarId: String?,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val key = avatarId?.lowercase()?.trim().takeIf { !it.isNullOrBlank() } ?: "beard_man"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            "beard_man" -> CharacterAvatarCanvas(size, bgColor = Color(0xFFE74C3C), type = "beard_man")
            "glasses_woman" -> CharacterAvatarCanvas(size, bgColor = Color(0xFFF39C12), type = "glasses_woman")
            "casual_guy" -> CharacterAvatarCanvas(size, bgColor = Color(0xFF2ECC71), type = "casual_guy")
            "redhead" -> CharacterAvatarCanvas(size, bgColor = Color(0xFF1ABC9C), type = "redhead")
            "business_man" -> CharacterAvatarCanvas(size, bgColor = Color(0xFF8E44AD), type = "business_man")
            "afro_woman" -> CharacterAvatarCanvas(size, bgColor = Color(0xFF16A085), type = "afro_woman")
            "bun_woman" -> CharacterAvatarCanvas(size, bgColor = Color(0xFFE67E22), type = "bun_woman")
            "cool_specs" -> CharacterAvatarCanvas(size, bgColor = Color(0xFF34495E), type = "cool_specs")
            "executive" -> CharacterAvatarCanvas(size, bgColor = Color(0xFFC0392B), type = "executive")
            "braid_woman" -> CharacterAvatarCanvas(size, bgColor = Color(0xFF27AE60), type = "braid_woman")
            else -> CharacterAvatarCanvas(size, bgColor = Color(0xFFE74C3C), type = "beard_man")
        }
    }
}

/**
 * Vector Illustrated Character Avatar Canvas matching the circular portrait style in the reference image.
 */
@Composable
private fun CharacterAvatarCanvas(size: Dp, bgColor: Color, type: String) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = w / 2f

        // Solid circular color background
        drawCircle(color = bgColor, radius = radius, center = center)

        val skinTone = when (type) {
            "afro_woman" -> Color(0xFF8D5524)
            "casual_guy", "business_man" -> Color(0xFFF1C27D)
            else -> Color(0xFFFFDBAC)
        }
        val darkHair = Color(0xFF2C3E50)
        val brownHair = Color(0xFF6E2C00)
        val redHair = Color(0xFFE67E22)
        val silverHair = Color(0xFFEAEDED)

        when (type) {
            "beard_man" -> {
                // Torso / Outfit
                drawArc(
                    color = Color(0xFF2980B9),
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(w * 0.12f, h * 0.58f),
                    size = Size(w * 0.76f, h * 0.70f),
                )
                // White shirt collar
                val collar = Path().apply {
                    moveTo(w * 0.40f, h * 0.62f)
                    lineTo(w * 0.50f, h * 0.78f)
                    lineTo(w * 0.60f, h * 0.62f)
                    close()
                }
                drawPath(collar, Color.White)

                // Neck
                drawRect(skinTone, topLeft = Offset(w * 0.42f, h * 0.50f), size = Size(w * 0.16f, h * 0.16f))

                // Head
                drawOval(skinTone, topLeft = Offset(w * 0.28f, h * 0.22f), size = Size(w * 0.44f, h * 0.42f))

                // Hair
                val hairPath = Path().apply {
                    moveTo(w * 0.26f, h * 0.38f)
                    cubicTo(w * 0.24f, h * 0.18f, w * 0.76f, h * 0.18f, w * 0.74f, h * 0.38f)
                    lineTo(w * 0.68f, h * 0.30f)
                    cubicTo(w * 0.60f, h * 0.22f, w * 0.40f, h * 0.22f, w * 0.32f, h * 0.30f)
                    close()
                }
                drawPath(hairPath, darkHair)

                // Beard
                val beardPath = Path().apply {
                    moveTo(w * 0.30f, h * 0.42f)
                    cubicTo(w * 0.30f, h * 0.65f, w * 0.70f, h * 0.65f, w * 0.70f, h * 0.42f)
                    lineTo(w * 0.62f, h * 0.46f)
                    cubicTo(w * 0.55f, h * 0.58f, w * 0.45f, h * 0.58f, w * 0.38f, h * 0.46f)
                    close()
                }
                drawPath(beardPath, darkHair)

                // Eyes & Smile
                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.41f, h * 0.38f))
                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.59f, h * 0.38f))
            }

            "glasses_woman" -> {
                // Outfit
                drawArc(
                    color = Color(0xFF212F3D),
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(w * 0.14f, h * 0.56f),
                    size = Size(w * 0.72f, h * 0.70f),
                )
                drawRect(skinTone, topLeft = Offset(w * 0.42f, h * 0.48f), size = Size(w * 0.16f, h * 0.16f))
                drawOval(skinTone, topLeft = Offset(w * 0.29f, h * 0.20f), size = Size(w * 0.42f, h * 0.42f))

                // Hair in bun
                drawCircle(darkHair, radius = w * 0.14f, center = Offset(w * 0.50f, h * 0.18f))
                drawArc(darkHair, startAngle = 160f, sweepAngle = 220f, useCenter = true, topLeft = Offset(w * 0.25f, h * 0.18f), size = Size(w * 0.50f, h * 0.30f))

                // Glasses
                drawRoundRect(Color(0xFF17202A), topLeft = Offset(w * 0.33f, h * 0.34f), size = Size(w * 0.15f, h * 0.10f), cornerRadius = CornerRadius(w * 0.02f), style = Stroke(w * 0.03f))
                drawRoundRect(Color(0xFF17202A), topLeft = Offset(w * 0.52f, h * 0.34f), size = Size(w * 0.15f, h * 0.10f), cornerRadius = CornerRadius(w * 0.02f), style = Stroke(w * 0.03f))
                drawLine(Color(0xFF17202A), start = Offset(w * 0.48f, h * 0.39f), end = Offset(w * 0.52f, h * 0.39f), strokeWidth = w * 0.03f)

                // Eyes & Smile
                drawCircle(Color(0xFF17202A), radius = w * 0.025f, center = Offset(w * 0.40f, h * 0.39f))
                drawCircle(Color(0xFF17202A), radius = w * 0.025f, center = Offset(w * 0.60f, h * 0.39f))
                drawArc(Color(0xFFC0392B), startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(w * 0.44f, h * 0.48f), size = Size(w * 0.12f, h * 0.08f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
            }

            "casual_guy" -> {
                drawArc(Color(0xFF1B2631), startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.12f, h * 0.55f), size = Size(w * 0.76f, h * 0.70f))
                drawRect(skinTone, topLeft = Offset(w * 0.42f, h * 0.48f), size = Size(w * 0.16f, h * 0.16f))
                drawOval(skinTone, topLeft = Offset(w * 0.28f, h * 0.20f), size = Size(w * 0.44f, h * 0.42f))

                // Short dark hair
                val hair = Path().apply {
                    moveTo(w * 0.25f, h * 0.32f)
                    cubicTo(w * 0.25f, h * 0.12f, w * 0.75f, h * 0.12f, w * 0.75f, h * 0.32f)
                    lineTo(w * 0.65f, h * 0.28f)
                    cubicTo(w * 0.55f, h * 0.22f, w * 0.45f, h * 0.22f, w * 0.35f, h * 0.28f)
                    close()
                }
                drawPath(hair, darkHair)

                // Round Specs
                drawCircle(Color(0xFF111111), radius = w * 0.08f, center = Offset(w * 0.40f, h * 0.38f), style = Stroke(w * 0.03f))
                drawCircle(Color(0xFF111111), radius = w * 0.08f, center = Offset(w * 0.60f, h * 0.38f), style = Stroke(w * 0.03f))
                drawLine(Color(0xFF111111), start = Offset(w * 0.48f, h * 0.38f), end = Offset(w * 0.52f, h * 0.38f), strokeWidth = w * 0.03f)

                drawCircle(Color(0xFF17202A), radius = w * 0.025f, center = Offset(w * 0.40f, h * 0.38f))
                drawCircle(Color(0xFF17202A), radius = w * 0.025f, center = Offset(w * 0.60f, h * 0.38f))
            }

            "redhead" -> {
                // Long red hair backdrop
                drawArc(redHair, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.20f, h * 0.20f), size = Size(w * 0.60f, h * 0.65f))
                drawArc(Color(0xFF7D3C98), startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.14f, h * 0.58f), size = Size(w * 0.72f, h * 0.70f))
                drawRect(skinTone, topLeft = Offset(w * 0.42f, h * 0.48f), size = Size(w * 0.16f, h * 0.16f))
                drawOval(skinTone, topLeft = Offset(w * 0.29f, h * 0.20f), size = Size(w * 0.42f, h * 0.42f))

                // Red bang hair
                val bangs = Path().apply {
                    moveTo(w * 0.28f, h * 0.28f)
                    cubicTo(w * 0.40f, h * 0.16f, w * 0.60f, h * 0.16f, w * 0.72f, h * 0.28f)
                    lineTo(w * 0.60f, h * 0.26f)
                    cubicTo(w * 0.50f, h * 0.22f, w * 0.40f, h * 0.22f, w * 0.28f, h * 0.28f)
                    close()
                }
                drawPath(bangs, redHair)

                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.40f, h * 0.38f))
                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.60f, h * 0.38f))
                drawArc(Color(0xFFC0392B), startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(w * 0.44f, h * 0.46f), size = Size(w * 0.12f, h * 0.08f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
            }

            "business_man" -> {
                drawArc(Color(0xFF1B2631), startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.12f, h * 0.56f), size = Size(w * 0.76f, h * 0.70f))
                val tie = Path().apply {
                    moveTo(w * 0.47f, h * 0.62f)
                    lineTo(w * 0.53f, h * 0.62f)
                    lineTo(w * 0.55f, h * 0.88f)
                    lineTo(w * 0.50f, h * 0.94f)
                    lineTo(w * 0.45f, h * 0.88f)
                    close()
                }
                drawPath(tie, Color(0xFFC0392B))
                drawRect(skinTone, topLeft = Offset(w * 0.42f, h * 0.48f), size = Size(w * 0.16f, h * 0.16f))
                drawOval(skinTone, topLeft = Offset(w * 0.28f, h * 0.20f), size = Size(w * 0.44f, h * 0.42f))

                // Slick dark hair & trim beard
                val hair = Path().apply {
                    moveTo(w * 0.26f, h * 0.30f)
                    cubicTo(w * 0.26f, h * 0.14f, w * 0.74f, h * 0.14f, w * 0.74f, h * 0.30f)
                    lineTo(w * 0.62f, h * 0.24f)
                    cubicTo(w * 0.50f, h * 0.20f, w * 0.38f, h * 0.20f, w * 0.26f, h * 0.30f)
                    close()
                }
                drawPath(hair, darkHair)

                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.41f, h * 0.38f))
                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.59f, h * 0.38f))
            }

            "afro_woman" -> {
                // Afro hair background
                drawCircle(Color(0xFF111111), radius = w * 0.32f, center = Offset(w * 0.50f, h * 0.34f))
                drawArc(Color(0xFFF1C40F), startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.14f, h * 0.58f), size = Size(w * 0.72f, h * 0.70f))
                drawRect(skinTone, topLeft = Offset(w * 0.42f, h * 0.48f), size = Size(w * 0.16f, h * 0.16f))
                drawOval(skinTone, topLeft = Offset(w * 0.30f, h * 0.22f), size = Size(w * 0.40f, h * 0.40f))

                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.41f, h * 0.38f))
                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.59f, h * 0.38f))
                drawArc(Color(0xFFC0392B), startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(w * 0.44f, h * 0.46f), size = Size(w * 0.12f, h * 0.08f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
            }

            else -> {
                // General Character Avatar
                drawArc(Color(0xFF2C3E50), startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.14f, h * 0.56f), size = Size(w * 0.72f, h * 0.70f))
                drawRect(skinTone, topLeft = Offset(w * 0.42f, h * 0.48f), size = Size(w * 0.16f, h * 0.16f))
                drawOval(skinTone, topLeft = Offset(w * 0.28f, h * 0.20f), size = Size(w * 0.44f, h * 0.42f))

                val hair = Path().apply {
                    moveTo(w * 0.26f, h * 0.30f)
                    cubicTo(w * 0.26f, h * 0.14f, w * 0.74f, h * 0.14f, w * 0.74f, h * 0.30f)
                    lineTo(w * 0.62f, h * 0.24f)
                    cubicTo(w * 0.50f, h * 0.20f, w * 0.38f, h * 0.20f, w * 0.26f, h * 0.30f)
                    close()
                }
                drawPath(hair, darkHair)

                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.41f, h * 0.38f))
                drawCircle(Color(0xFF17202A), radius = w * 0.03f, center = Offset(w * 0.59f, h * 0.38f))
            }
        }
    }
}


