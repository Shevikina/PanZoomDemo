package com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.shevikina.panzoomdemo.domain.model.PanBounds
import com.shevikina.panzoomdemo.domain.model.PanOffset
import com.shevikina.panzoomdemo.domain.usecase.IClampPanOffsetUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Состояние жеста pan и zoom.
 *
 * Хранит масштаб, смещение и инерцию.
 */
class PanZoomState(
    private val clampPanOffset: IClampPanOffsetUseCase,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private val velocityTracker = VelocityTracker()

    // Границы для clamp `offset`
    private var bounds: PanBounds = PanBounds.UNSPECIFIED

    // Обе оси инерции под одним родителем, чтобы одна ссылка отменяла их разом.
    private var flingJob: Job? = null

    /**
     * Задаёт размеры viewport и размеры уже отрисованного содержимого для ограничения [offset].
     *
     * Наивный вьюер вписывает картинку в экран внутри отрисовки, поэтому передаёт сюда
     * вписанный размер, а не исходный.
     *
     * Стартовый масштаб тут не трогается намеренно.
     */
    fun setBounds(
        viewportW: Float,
        viewportH: Float,
        imageW: Float,
        imageH: Float,
    ) {
        bounds = PanBounds(
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            imageWidth = imageW,
            imageHeight = imageH,
        )
        offset = clampOffset(offset, scale)
    }

    /** Делегирует ограничение офсета в domain, конвертируя Compose-тип в доменный. */
    private fun clampOffset(
        value: Offset,
        currentScale: Float,
    ): Offset {
        val clamped = clampPanOffset(PanOffset(value.x, value.y), currentScale, bounds)
        return Offset(clamped.x, clamped.y)
    }

    /**
     * @param zoom множитель масштаба за кадр жеста.
     * @param centroid точка сведения пальцев в координатах экрана. Остаётся неподвижной под пальцами.
     *
     * Итоговый [scale] ограничен диапазоном [MIN_SCALE, MAX_SCALE].
     * Формула: `offset_new = centroid - (centroid - offset_old) * (newScale / scale)`.
     */
    fun onZoom(
        zoom: Float,
        centroid: Offset,
    ) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == scale) return
        val k = newScale / scale
        val newOffset = Offset(
            x = centroid.x - (centroid.x - offset.x) * k,
            y = centroid.y - (centroid.y - offset.y) * k,
        )
        scale = newScale
        offset = clampOffset(newOffset, newScale)
    }

    fun onPan(
        pan: Offset,
        uptimeMillis: Long,
        position: Offset,
    ) {
        offset = clampOffset(offset + pan, scale)
        velocityTracker.addPosition(uptimeMillis, position)
    }

    /**
     * Определяет, идёт ли сейчас инерционное скольжение после отпускания пальца.
     *
     * Читается вьюером и тестом.
     * */
    val isFlinging: Boolean
        get() = flingJob?.isActive == true

    /**
     * Останавливает инерцию.
     *
     * Вызывать при касании, чтобы жест и анимация не записывали в один offset,
     * а на замерах лишние анимации не подмешивали кадры туда, где меряется джанк.
     */
    fun stopFling() {
        flingJob?.cancel()
        flingJob = null
    }

    /**
     * Запускает инерционное скольжение после отпускания пальца.
     *
     * X и Y анимируются независимо. По достижении границы соответствующая ось останавливается.
     * Перед запуском гасит предыдущую инерцию.
     *
     * @param scope отмена скоупа немедленно останавливает fling.
     */
    fun fling(scope: CoroutineScope) {
        val velocity = velocityTracker.calculateVelocity()
        velocityTracker.resetTracking()

        val decay = exponentialDecay<Float>(frictionMultiplier = 2f)

        stopFling()
        flingJob = scope.launch {
            launch {
                AnimationState(
                    initialValue = offset.x,
                    initialVelocity = velocity.x,
                ).animateDecay(decay) {
                    val clamped = clampOffset(offset.copy(x = value), scale)
                    offset = clamped
                    if (clamped.x != value) cancelAnimation()
                }
            }
            launch {
                AnimationState(
                    initialValue = offset.y,
                    initialVelocity = velocity.y,
                ).animateDecay(decay) {
                    val clamped = clampOffset(offset.copy(y = value), scale)
                    offset = clamped
                    if (clamped.y != value) cancelAnimation()
                }
            }
        }
    }

    private companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10f
    }
}
