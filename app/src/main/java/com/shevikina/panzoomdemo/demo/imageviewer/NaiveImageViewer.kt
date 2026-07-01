package com.shevikina.panzoomdemo.demo.imageviewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.shevikina.panzoomdemo.models.states.PanZoomState
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

// Demo 1: полная загрузка битмапа → OOM на большой картинке
@Composable
fun NaiveImageViewer(
    assetName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Блокирующая загрузка всего файла в память (намеренно плохо)
    val bitmap = remember(assetName) {
        context.assets
            .open(assetName)
            .use { BitmapFactory.decodeStream(it) }
            ?: error("Failed to decode $assetName")
    }

    val state = remember { PanZoomState() }

    NaiveImageViewerContent(
        bitmap = bitmap.asImageBitmap(),
        scale = state.scale,
        offset = state.offset,
        onGesture = { centroid, pan, zoom ->
            state.onPan(pan, 0L, centroid)
            state.onZoom(zoom, centroid)
        },
        modifier = modifier
    )
}

@Composable
private fun NaiveImageViewerContent(
    bitmap: ImageBitmap,
    scale: Float,
    offset: Offset,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ -> onGesture(centroid, pan, zoom) }
            }
    ) {
        // Параметрическая форма graphicsLayer читает state в Composition → каждый MOVE вызывает рекомпозицию.
        // Намеренная ошибка для Demo 1.
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun NaiveImageViewerContentPreview() {
    PanZoomDemoTheme {
        NaiveImageViewerContent(
            bitmap = bitmapForPreview,
            scale = 1.4f,
            offset = Offset(5f, 3f),
            onGesture = { _, _, _ -> }
        )
    }
}

private val bitmapForPreview
    get() = ImageBitmap(500, 500).also { imageBitmap ->
        Canvas(imageBitmap).drawRect(
            left = 0f,
            top = 0f,
            right = 500f,
            bottom = 500f,
            paint = Paint().apply { color = Color(0xFF4CAF50) }
        )
    }