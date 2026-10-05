package com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope

/**
 * Механика жеста pan/zoom
 *
 * Намеренно сделана общей для наивного и тайлового вьюеров:
 * разница между режимами должна быть только в том, что демонстрируется.
 *
 * key=[PanZoomState] в [pointerInput]:
 * Ссылка стабильна внутри одного изображения, поэтому pointer-пайплайн настраивается один раз.
 * При смене изображения состояние приходит новым объектом, и корутина жеста должна перезапуститься.
 */
internal fun Modifier.panZoomGestures(
    state: PanZoomState,
    scope: CoroutineScope,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // Касание останавливает инерцию, чтобы палец и decay-анимация
        // не тянули offset каждый в свою сторону.
        state.stopFling()
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val centroid = event.calculateCentroid(useCurrent = false)
            val uptimeMillis = event.changes.first().uptimeMillis

            state.onZoom(zoom, centroid)
            state.onPan(pan, uptimeMillis, centroid)

            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })

        state.fling(scope)
    }
}
