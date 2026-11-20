package com.music.spotui.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.music.spotui.ui.immersive.sheets.ImmersiveGlassSheet

/**
 * The bottom sheet across the app: always renders [ImmersiveGlassSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    ImmersiveGlassSheet(onDismissRequest, sheetState, content)
}
