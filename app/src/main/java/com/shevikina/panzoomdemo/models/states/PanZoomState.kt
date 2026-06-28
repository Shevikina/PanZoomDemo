package com.shevikina.panzoomdemo.models.states

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * Состояние жеста pan/zoom
 */
class PanZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private val velocityTracker = VelocityTracker()

    /**
     * @param zoom множитель масштаба за один кадр жеста (1.0 = без изменений).
     * Итоговый [scale] ограничен диапазоном [0.1, 10].
     *
     * Нижняя граница 0.1 позволяет LOD сработать:
     * - inSampleSize=2 при scale<0.5
     * - inSampleSize=4 при scale<0.25
     * - inSampleSize=8 при scale<0.125
     */
    fun onZoom(zoom: Float) {
        scale = (scale * zoom).coerceIn(0.1f, 10f)
    }

    fun onPan(
        pan: Offset,
        uptimeMillis: Long,
        position: Offset
    ) {
        offset += pan
        velocityTracker.addPosition(uptimeMillis, position)
    }
}
