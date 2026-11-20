package com.music.spotui.ui.look

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wavebits.engine.R
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.LookPref

/** Set when the step is the "new look" popup shown to existing users: its own title, buttons and note. */
class LookOffer(val onKeep: () -> Unit)

private val ImmersivePreviewPalettes = listOf(
    MeshPalette(listOf(Color(0xFF1E3264), Color(0xFF8D67AB), Color(0xFF121212), Color(0xFF1ED760))),
    MeshPalette(listOf(Color(0xFF472D61), Color(0xFFAF2896), Color(0xFF181818), Color(0xFFE8115B))),
    MeshPalette(listOf(Color(0xFF0D73EC), Color(0xFF509BF5), Color(0xFF121212), Color(0xFF1DB954))),
)

/**
 * Onboarding look step for the Immersive player aesthetic.
 */
@Composable
fun LookStep(onNext: () -> Unit, offer: LookOffer? = null) {
    val context = LocalContext.current
    val family = ImmersiveSans
    val text = TextStyle(fontFamily = family, color = Color.White)
    val subtle = Color.White.copy(alpha = 0.72f)
    val palette = cyclingMeshPalette(ImmersivePreviewPalettes)

    remember {
        LookPref.set(context, Look.IMMERSIVE)
        true
    }

    Box(Modifier.fillMaxSize()) {
        MeshGradientBackground(palette = palette, colorFadeMillis = MESH_CYCLE_MILLIS)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (offer != null) R.string.look_offer_title else R.string.setup_look_title),
                style = text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.look_immersive_detail),
                style = text,
                color = subtle,
                fontSize = 14.sp,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.look_immersive),
                        style = text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.look_immersive_detail),
                        style = text,
                        color = subtle,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("look_continue_button"),
            ) {
                Text(
                    stringResource(if (offer != null) R.string.look_offer_try else R.string.setup_continue),
                    fontFamily = family,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (offer != null) {
                TextButton(
                    onClick = offer.onKeep,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("look_keep_button"),
                ) {
                    Text(
                        stringResource(R.string.look_offer_keep),
                        color = Color.White,
                        fontFamily = family,
                        fontSize = 15.sp,
                    )
                }
                Text(
                    stringResource(R.string.look_offer_note),
                    style = text,
                    color = subtle.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
