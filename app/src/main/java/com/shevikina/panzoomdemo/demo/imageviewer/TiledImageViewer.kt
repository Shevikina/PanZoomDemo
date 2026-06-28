package com.shevikina.panzoomdemo.demo.imageviewer

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Composable
fun TiledImageViewer(
    assetName: String,
    checkedCulling: Boolean,
    checkedLod: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberTiledImageViewerState(assetName)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        LaunchedEffect(state.panZoom.scale, state.panZoom.offset, checkedCulling, checkedLod) {
            state.updateTiles(
                scope = this,
                screenW = screenW,
                screenH = screenH,
                checkedCulling = checkedCulling,
                checkedLod = checkedLod
            )
        }

        TiledImageViewerContent(
            activeTiles = state.activeTiles,
            scale = state.panZoom.scale,
            offset = state.panZoom.offset,
            onGesture = { pan, zoom ->
                state.panZoom.onPan(pan)
                state.panZoom.onZoom(zoom)
            }
        )
    }
}

@Composable
private fun TiledImageViewerContent(
    activeTiles: Map<Rect, Bitmap>,
    scale: Float,
    offset: Offset,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ -> onGesture(pan, zoom) }
            }
    ) {
        activeTiles.forEach { (rect, bmp) ->
            drawImage(
                image = bmp.asImageBitmap(),
                dstOffset = IntOffset(
                    x = (rect.left * scale + offset.x).toInt(),
                    y = (rect.top * scale + offset.y).toInt(),
                ),
                dstSize = IntSize(
                    width = (rect.width() * scale).toInt(),
                    height = (rect.height() * scale).toInt(),
                )
            )
        }
    }
}
