package com.music.spotui.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder

const val UPI_ID = "captainbl1z3.in@okhdfcbank"
const val UPI_PAYMENT_URL = "upi://pay?pa=captainbl1z3.in@okhdfcbank&pn=WaveBits&cu=INR"
const val UPI_QR_IMAGE_URL = "https://raw.githubusercontent.com/tpxlegend/WaveBits/main/assets/Screenshot_20261106-114557.jpg"

/**
 * Buy Me a Coffee UPI donation sheet matching the user's design:
 * Header pill, title, subtitle, white QR Code card displaying the exact image asset,
 * UPI ID, Copy ID button, and Open in UPI App button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportUpiSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.wavebits.extensions.ProtectedSurface.SUPPORT)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF141214),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            // Pill Badge: Support Developer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF381B24))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Coffee,
                    contentDescription = null,
                    tint = Color(0xFFF09886),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Support Developer",
                    color = Color(0xFFF09886),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(14.dp))

            // Title
            Text(
                text = "Buy Me a Coffee",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // Subtitle
            Text(
                text = "Keep WaveBits 100% free, ad-free, and open for everyone. Scan with any UPI app to support!",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(Modifier.height(24.dp))

            // White QR Code Card displaying the requested image asset
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White)
                    .padding(vertical = 24.dp, horizontal = 20.dp)
            ) {
                // QR Image View
                UpiQrCodeView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )

                Spacer(Modifier.height(16.dp))

                // UPI ID Text
                Text(
                    text = UPI_ID,
                    color = Color(0xFF1E2022),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Copy ID Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF2C2224))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("UPI ID", UPI_ID))
                            Toast.makeText(context, "UPI ID copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Copy ID",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Open in UPI App Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFF09886))
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UPI_PAYMENT_URL)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(intent)
                            }.onFailure {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("UPI ID", UPI_ID))
                                Toast.makeText(context, "UPI ID copied. Open your UPI app to pay.", Toast.LENGTH_LONG).show()
                            }
                        }
                ) {
                    Text(
                        text = "Open in UPI App",
                        color = Color(0xFF221114),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Footer Note
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = Color(0xFFE94560),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Thank you for supporting independent music software!",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Loads the exact requested QR code image from the repository asset with canvas fallback */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun UpiQrCodeView(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        GlideImage(
            model = UPI_QR_IMAGE_URL,
            contentDescription = "UPI QR Code",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp)),
            loading = placeholder {
                UpiQrCodeCanvas(modifier = Modifier.fillMaxSize())
            },
            failure = placeholder {
                UpiQrCodeCanvas(modifier = Modifier.fillMaxSize())
            }
        )
    }
}

/** Crisp vector Canvas QR Code matrix fallback */
@Composable
private fun UpiQrCodeCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val grid = arrayOf(
            "11111110010101110101111111",
            "10000010100011000001000001",
            "10111010110100101101011101",
            "10111010011110011001011101",
            "10111010101001000101011101",
            "10000010001111101001000001",
            "11111110101010101011111111",
            "00000000110011010000000000",
            "10101101101101100111010101",
            "01110010010110011000110010",
            "10001111101001110101101101",
            "01101010011010001110010100",
            "11010101100101101001101111",
            "00110010111110010111001010",
            "10101111010011101000110101",
            "01100100110100011101011000",
            "11111011101101000011101101",
            "00000000100110110111001010",
            "11111110011011010001010101",
            "10000010110100101110100110",
            "10111010010111000101110101",
            "10111010101100111010001100",
            "10111010011010010111010101",
            "10000010110111001000110010",
            "11111110010010110111010111"
        )

        val rows = grid.size
        val cols = grid[0].length
        val cellW = width / cols
        val cellH = height / rows

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] == '1') {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(c * cellW, r * cellH),
                        size = Size(cellW * 1.02f, cellH * 1.02f)
                    )
                }
            }
        }
    }
}
