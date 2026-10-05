package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled

import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture.PanZoomState
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture.panZoomGestures
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects.RecomposeLogEffect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.DrawableTile

/**
 * Выбирает способ отрисовки по тумблеру graphicsLayer.
 *
 * Разница веток в том, в какой фазе читаются scale и offset.
 */
@Composable
internal fun TiledImageViewerContent(
    activeTiles: Map<TileRect, DrawableTile>,
    fallbackTiles: Map<TileRect, DrawableTile>,
    overview: DrawableTile?,
    imageWidth: Int,
    imageHeight: Int,
    revisionState: IntState,
    panZoom: PanZoomState,
    useGraphicsLayer: Boolean,
    modifier: Modifier = Modifier,
) {
    val overviewImage = overview?.image
    val scope = rememberCoroutineScope()
    val gestureModifier = Modifier.panZoomGestures(panZoom, scope)

    RecomposeLogEffect(tag = "Content") {
        "tiles=${activeTiles.size} graphicsLayer=$useGraphicsLayer"
    }

    if (useGraphicsLayer) {
        TiledCanvasWithLateTransformation(
            panZoom = panZoom,
            gestureModifier = gestureModifier,
            revisionState = revisionState,
            overviewImage = overviewImage,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            fallbackTiles = fallbackTiles,
            activeTiles = activeTiles,
            modifier = modifier,
        )
    } else {
        TiledCanvasWithEarlyTransformation(
            panZoom = panZoom,
            gestureModifier = gestureModifier,
            revisionState = revisionState,
            overviewImage = overviewImage,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            fallbackTiles = fallbackTiles,
            activeTiles = activeTiles,
            modifier = modifier,
        )
    }
}
