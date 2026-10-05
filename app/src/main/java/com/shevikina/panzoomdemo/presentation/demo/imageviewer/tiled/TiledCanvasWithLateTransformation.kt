package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture.PanZoomState
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.DrawableTile

/**
 * Рисует тайлы и подложку. Масштаб со смещением отдаёт GPU через graphicsLayer.
 *
 * Значения читаются внутри лямбды graphicsLayer(в фазе отрисовки), поэтому под жестом
 * Canvas не перерисовывается и композиция не запускается. Это и есть поздняя трансформация.
 *
 * Порядок модификаторов важен!
 * gestureModifier стоит перед graphicsLayer,
 * потому что graphicsLayer разворачивает координаты обратно для всех вложенных модификаторов,
 * и при scale меньше единицы hit-test перестаёт попадать.
 * Снаружи слоя pointerInput работает в координатах экрана и жесты принимает верно.
 */
@Composable
internal fun TiledCanvasWithLateTransformation(
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
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier)
            .graphicsLayer {
                translationX = panZoom.offset.x
                translationY = panZoom.offset.y
                scaleX = panZoom.scale
                scaleY = panZoom.scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        // Чтение внутри DrawScope подписывает на revision только отрисовку, без рекомпозиции.
        // Значение не нужно, нужен сам факт чтения.
        revisionState.intValue
        if (overviewImage != null) {
            drawImage(
                image = overviewImage,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(imageWidth, imageHeight),
                filterQuality = FilterQuality.Medium,
            )
        }
        drawTilesAtImageCoordinates(fallbackTiles)
        drawTilesAtImageCoordinates(activeTiles)
    }
}
