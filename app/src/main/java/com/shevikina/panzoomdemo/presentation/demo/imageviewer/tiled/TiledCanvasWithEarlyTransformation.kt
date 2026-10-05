package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture.PanZoomState
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.DrawableTile

/**
 * Рисует тайлы, пересчитывая экранные координаты на CPU по scale и offset.
 *
 * Значения читаются в теле композабла, то есть в фазе Composition, поэтому каждое движение
 * пальца вызывает рекомпозицию, а Skia пересобирает draw-list. Это намеренно плохая ветка,
 * и сравнивают её с [TiledCanvasWithLateTransformation].
 */
@Composable
internal fun TiledCanvasWithEarlyTransformation(
    panZoom: PanZoomState,
    gestureModifier: Modifier,
    revisionState: IntState,
    overviewImage: ImageBitmap?,
    imageWidth: Int,
    imageHeight: Int,
    fallbackTiles: Map<TileRect, DrawableTile>,
    activeTiles: Map<TileRect, DrawableTile>,
    modifier: Modifier = Modifier,
) {
    val scale = panZoom.scale
    val offset = panZoom.offset

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier),
    ) {
        // Чтение внутри DrawScope подписывает на revision только отрисовку, без рекомпозиции.
        // Значение не нужно, нужен сам факт чтения.
        revisionState.intValue

        if (overviewImage != null) {
            drawImage(
                image = overviewImage,
                dstOffset = IntOffset(offset.x.toInt(), offset.y.toInt()),
                dstSize = IntSize(
                    width = (imageWidth * scale).toInt(),
                    height = (imageHeight * scale).toInt(),
                ),
                filterQuality = FilterQuality.Medium,
            )
        }
        drawTiles(fallbackTiles, scale, offset)
        drawTiles(activeTiles, scale, offset)
    }
}
