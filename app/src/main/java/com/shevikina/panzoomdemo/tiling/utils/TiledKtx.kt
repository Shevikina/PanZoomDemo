package com.shevikina.panzoomdemo.tiling.utils

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset

fun screenRectToImageRect(
    screenWidth: Float,
    screenHeight: Float,
    offset: Offset,
    scale: Float,
): RectF {
    val left = (-offset.x / scale).coerceAtLeast(0f)
    val top = (-offset.y / scale).coerceAtLeast(0f)
    val right = (screenWidth - offset.x) / scale
    val bottom = (screenHeight - offset.y) / scale
    return RectF(left, top, right, bottom)
}