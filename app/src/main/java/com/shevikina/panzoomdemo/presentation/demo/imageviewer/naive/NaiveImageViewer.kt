package com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture.panZoomGestures

/**
 * Показывает изображение, декодированное в память целиком.
 *
 * Это первый демонстрационный режим, который показывает решение задачи "в лоб".
 * На большой картинке он приводит к OOM.
 */
@Composable
internal fun NaiveImageViewer(
    asset: TestImageAsset,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onError: (ImageLoadError) -> Unit = {},
) {
    rememberNaiveImageViewerState(asset, onError)?.let { state ->
        LaunchedEffect(state) { onReady() }

        DrawFailureEffect(state, onError)

        // Повторять отрисовку нельзя, потому что платформа будет отклонять её на каждом кадре.
        if (!state.isDrawRejected) {
            val scope = rememberCoroutineScope()
            NaiveImageViewerContent(
                bitmap = state.imageBitmap,
                scale = state.panZoom.scale,
                offset = state.panZoom.offset,
                gestureModifier = Modifier.panZoomGestures(state.panZoom, scope),
                onViewportChanged = state::onViewportChanged,
                onDrawFailure = state::reportDrawFailure,
                modifier = modifier,
            )
        }
    }
}
