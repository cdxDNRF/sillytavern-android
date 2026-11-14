package com.sillytavern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Compact draggable floating pill giving one-tap access to the Export Box & Engine Logs
 * without permanently obstructing any SillyTavern toolbar controls.
 */
@Composable
fun FloatingControlHandle(
    exportCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val handleSizePx = with(density) { 48.dp.toPx() }
        val edgePadPx = with(density) { 10.dp.toPx() }

        var initialized by remember { mutableStateOf(false) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        if (!initialized && maxWidthPx > 0f && maxHeightPx > 0f) {
            offsetX = (maxWidthPx - handleSizePx - edgePadPx).coerceAtLeast(0f)
            offsetY = (maxHeightPx * 0.62f).coerceIn(edgePadPx, (maxHeightPx - handleSizePx - edgePadPx).coerceAtLeast(edgePadPx))
            initialized = true
        }

        val clampedX = offsetX.coerceIn(0f, (maxWidthPx - handleSizePx).coerceAtLeast(0f))
        val clampedY = offsetY.coerceIn(0f, (maxHeightPx - handleSizePx).coerceAtLeast(0f))

        Surface(
            shape = CircleShape,
            color = Color(0xCC1E212E),
            shadowElevation = 6.dp,
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .alpha(0.86f)
                .pointerInput(maxWidthPx, maxHeightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, (maxWidthPx - handleSizePx).coerceAtLeast(0f))
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, (maxHeightPx - handleSizePx).coerceAtLeast(0f))
                    }
                }
                .clickable(onClick = onClick),
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderZip,
                    contentDescription = "打开导出箱与工具",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
                if (exportCount > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            text = if (exportCount > 99) "99+" else exportCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
