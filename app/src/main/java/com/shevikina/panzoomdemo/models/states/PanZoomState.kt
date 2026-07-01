package com.shevikina.panzoomdemo.models.states

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Состояние жеста pan/zoom
 */
class PanZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private val velocityTracker = VelocityTracker()

    // Границы для clamp `offset`. Считаются при изменении размеров viewport/изображения.
    // Пока не заданы (Size.Zero) — clamp не применяется (совместимый с до-bounds кодом режим).
    private var viewportSize: Size = Size.Zero
    private var imageSize: Size = Size.Zero

    /**
     * Задаёт размеры viewport и изображения для ограничения [offset]. Должно вызываться
     * один раз при появлении composable на экране (через `BoxWithConstraints`) и при смене
     * размеров. После вызова текущий [offset] поджимается под актуальные границы.
     */
    fun setBounds(viewportW: Float, viewportH: Float, imageW: Float, imageH: Float) {
        viewportSize = Size(viewportW, viewportH)
        imageSize = Size(imageW, imageH)
        offset = clampOffset(offset, scale)
    }

    /**
     * Сжимает [Offset] до границ, чтобы изображение никогда не уходило полностью за экран:
     * - если масштабированное изображение шире viewport — позволяем панорамировать в пределах
     *   `[viewport - imageScaled, 0]` (края изображения «прилипают» к краям viewport);
     * - если уже viewport — фиксируем `[0, viewport - imageScaled]` (картинка не уходит за рамки экрана).
     *
     * Когда bounds не заданы — пропускаем clamp (старое поведение).
     */
    private fun clampOffset(o: Offset, s: Float): Offset {
        if (viewportSize == Size.Zero || imageSize == Size.Zero) return o
        val imgW = imageSize.width * s
        val imgH = imageSize.height * s
        val xRange = boundsRange(viewportSize.width, imgW)
        val yRange = boundsRange(viewportSize.height, imgH)
        return Offset(
            x = o.x.coerceIn(xRange.first, xRange.second),
            y = o.y.coerceIn(yRange.first, yRange.second),
        )
    }

    private fun boundsRange(viewport: Float, contentScaled: Float): Pair<Float, Float> {
        // Диапазон значений `offset`, при которых хотя бы пиксель содержимого виден на экране.
        // (При viewport == contentScaled оба значения равны 0.)
        val lo = minOf(0f, viewport - contentScaled)
        val hi = maxOf(0f, viewport - contentScaled)
        return lo to hi
    }

    /**
     * @param zoom множитель масштаба за один кадр жеста (1.0 = без изменений).
     * @param centroid точка сведения пальцев в координатах viewport (screen-coords).
     *                 Сохраняется неподвижной под пальцами при масштабировании.
     *
     * Итоговый [scale] ограничен диапазоном [MIN_SCALE, MAX_SCALE].
     *
     * Нижняя граница позволяет LOD сработать:
     * - inSampleSize=2 при scale<0.5
     * - inSampleSize=4 при scale<0.25
     * - inSampleSize=8 при scale<0.125
     *
     * Формула «pivot вокруг centroid»: `offset_new = centroid - (centroid - offset_old) * k`,
     * где `k = scale_new / scale_old`. Это сохраняет image-coords проекцию centroid
     * `(centroid - offset) / scale` неизменной — точка под пальцами не уезжает.
     */
    fun onZoom(zoom: Float, centroid: Offset) {
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
     * Запуск инерционного скольжение после отпускания пальца.
     *
     * Берёт скорость, накопленную [velocityTracker] за последние MOVE-события,
     * и анимирует [offset] по экспоненциальному затуханию до полной остановки.
     *
     * X и Y анимируются независимо в двух параллельных корутинах. При достижении
     * края картинки соответствующая ось останавливается (`cancelAnimation`), другая
     * продолжает докатываться.
     *
     * @param scope корутин-скоуп, в котором живут анимации; отмена скоупа
     *              немедленно останавливает fling.
     */
    fun fling(scope: CoroutineScope) {
        val velocity = velocityTracker.calculateVelocity()
        velocityTracker.resetTracking()

        val decay = exponentialDecay<Float>(frictionMultiplier = 2f)

        scope.launch {
            AnimationState(
                initialValue = offset.x,
                initialVelocity = velocity.x,
            ).animateDecay(decay) {
                val clamped = clampOffset(offset.copy(x = value), scale)
                offset = clamped
                if (clamped.x != value) cancelAnimation()
            }
        }
        scope.launch {
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

    private companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10f
    }
}
