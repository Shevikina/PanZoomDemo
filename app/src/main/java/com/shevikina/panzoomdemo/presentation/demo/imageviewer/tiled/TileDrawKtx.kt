package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.DrawableTile

/**
 * Рисует тайлы в координатах экрана, пересчитывая положение каждого по scale и offset.
 *
 * Считает на CPU и делает это заново в каждом кадре жеста, потому что scale и offset
 * приходят новыми значениями.
 *
 * Ветку целиком описывает [TiledCanvasWithEarlyTransformation].
 */
internal fun DrawScope.drawTiles(
    tiles: Map<TileRect, DrawableTile>,
    scale: Float,
    offset: Offset,
) {
    tiles.forEach { (rect, tile) ->
        drawImage(
            image = tile.image,
            dstOffset = IntOffset(
                x = (rect.left * scale + offset.x).toInt(),
                y = (rect.top * scale + offset.y).toInt(),
            ),
            dstSize = IntSize(
                width = (rect.width * scale).toInt(),
                height = (rect.height * scale).toInt(),
            ),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/**
 * Рисует тайлы в координатах изображения, без пересчёта положения.
 *
 * Масштаб и смещение накладывает GPU через `Modifier.graphicsLayer`,
 * поэтому draw-list не меняется от кадра к кадру.
 *
 * Ветку целиком описывает [TiledCanvasWithLateTransformation].
 */
internal fun DrawScope.drawTilesAtImageCoordinates(tiles: Map<TileRect, DrawableTile>) {
    tiles.forEach { (rect, tile) ->
        drawImage(
            image = tile.image,
            dstOffset = IntOffset(rect.left, rect.top),
            dstSize = IntSize(rect.width, rect.height),
            filterQuality = FilterQuality.Medium,
        )
    }
}
