package com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme
import androidx.compose.foundation.Canvas as DrawCanvas

/**
 * Рисует картинку целиком и применяет масштаб через параметры graphicsLayer. Решение "в лоб"
 *
 * Параметрическая форма graphicsLayer читает scale и offset в Composition, поэтому каждое
 * движение пальца вызывает рекомпозицию.
 *
 * Битмап рисуется вручную, а не через Image, потому что drawImage синхронный, и отказ Skia
 * положить слишком большой битмап в display list ловится прямо здесь. Через Image то же
 * исключение улетело бы в фазу отрисовки и уронило приложение.
 */
@Suppress("TooGenericExceptionCaught")
@Composable
internal fun NaiveImageViewerContent(
    bitmap: ImageBitmap,
    scale: Float,
    offset: Offset,
    gestureModifier: Modifier,
    onViewportChanged: (width: Float, height: Float) -> Unit,
    modifier: Modifier = Modifier,
    onDrawFailure: (Throwable) -> Unit = {},
) {
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { onViewportChanged(it.width.toFloat(), it.height.toFloat()) }
            .then(gestureModifier),
    ) {
        DrawCanvas(
            // Параметрическая форма намеренно как решение в лоб.
            // Вызывает рекомпозицию на каждом кадре жеста
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            val fit = fitScale(size.width, size.height, bitmap.width, bitmap.height)
            try {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        width = (bitmap.width * fit).toInt(),
                        height = (bitmap.height * fit).toInt(),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
            } catch (e: RuntimeException) {
                // Исключение уходит как есть, чтобы до пользователя дошли тип и текст от платформы
                onDrawFailure(e)
            }
        }
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
            gestureModifier = Modifier,
            onViewportChanged = { _, _ -> },
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
            paint = Paint().apply { color = Color(0xFF4CAF50) },
        )
    }
