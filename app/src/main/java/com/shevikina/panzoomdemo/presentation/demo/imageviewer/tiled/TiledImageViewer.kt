package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects.DecodeFailureEffect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects.FrameLoopEffect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects.RecomposeLogEffect
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects.UpdateTilesOnGestureEffect

/**
 * Тайловый вьювер с culling, LOD и поздней трансформацией через graphicsLayer.
 *
 * Оптимизированная загрузка изображений
 */
@Composable
internal fun TiledImageViewer(
    source: ImageSource,
    checkedCulling: Boolean,
    checkedLod: Boolean,
    useGraphicsLayer: Boolean,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onError: (ImageLoadError) -> Unit = {},
    onTilesChanged: (held: Int, needed: Int) -> Unit = { _, _ -> },
) {
    val state = rememberTiledImageViewerState(source) ?: return
    LaunchedEffect(state) { onReady() }

    // Отдельный scope, чтобы задачи декода не отменялись при рестарте эффектов на каждом жесте.
    val tileScope = rememberCoroutineScope()

    DecodeFailureEffect(
        state = state,
        onError = onError,
    )
    RecomposeLogEffect(tag = "TiledImageViewer") {
        "activeTiles=${state.activeTiles.size}"
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        // Границы нужны клампу, чтобы offset не уводил viewport за край изображения.
        LaunchedEffect(state, screenW, screenH) {
            state.panZoom.setBounds(
                viewportW = screenW,
                viewportH = screenH,
                imageW = state.imageWidth,
                imageH = state.imageHeight,
            )
        }

        UpdateTilesOnGestureEffect(
            state = state,
            tileScope = tileScope,
            screenW = screenW,
            screenH = screenH,
            checkedCulling = checkedCulling,
            checkedLod = checkedLod,
        )
        FrameLoopEffect(
            state = state,
            tileScope = tileScope,
            onTilesChanged = onTilesChanged,
        )

        TiledImageViewerContent(
            activeTiles = state.activeTiles,
            fallbackTiles = state.fallbackTiles,
            overview = state.overview,
            imageWidth = state.imageWidth.toInt(),
            imageHeight = state.imageHeight.toInt(),
            revisionState = state.revision,
            panZoom = state.panZoom,
            useGraphicsLayer = useGraphicsLayer,
        )
    }
}
