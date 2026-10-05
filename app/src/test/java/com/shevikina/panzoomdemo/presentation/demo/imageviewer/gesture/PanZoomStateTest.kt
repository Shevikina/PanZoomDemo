package com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture

import androidx.compose.ui.geometry.Offset
import com.shevikina.panzoomdemo.domain.usecase.ClampPanOffsetUseCase
import com.shevikina.panzoomdemo.test.util.TestFrameClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PanZoomStateTest {

    private val state = PanZoomState(ClampPanOffsetUseCase())

    @Test
    fun `should not change scale when bounds are set`() {
        state.setBounds(VIEWPORT, VIEWPORT, IMAGE, IMAGE)

        // Вписывание в экран живёт в отрисовке наивного вьюера, а не в стартовом масштабе:
        // тайловому режиму такой старт обходится в полную сетку тайлов и лесенку LOD.
        state.scale shouldBe 1f
    }

    @Test
    fun `should clamp offset to bounds`() {
        state.setBounds(VIEWPORT, VIEWPORT, IMAGE, IMAGE)

        state.onPan(Offset(500f, -9999f), uptimeMillis = 0L, position = Offset.Zero)

        state.offset shouldBe Offset(0f, VIEWPORT - IMAGE)
    }

    @Test
    fun `should replace previous fling with a new one`() = runTest {
        val scope = flingScope()
        state.setBounds(VIEWPORT, VIEWPORT, IMAGE, IMAGE)
        swipe()

        state.fling(scope)
        advanceFrames()
        val afterFirst = state.offset
        swipe()
        state.fling(scope)
        advanceFrames()

        state.isFlinging shouldBe true
        state.offset shouldNotBe afterFirst

        // Прежняя инерция снята вместе с новой: после остановки offset больше не двигается.
        state.stopFling()
        val stopped = state.offset
        advanceFrames()
        state.offset shouldBe stopped
        scope.cancel()
    }

    @Test
    fun `should stop fling on touch`() = runTest {
        val scope = flingScope()
        state.setBounds(VIEWPORT, VIEWPORT, IMAGE, IMAGE)
        swipe()
        state.fling(scope)
        advanceFrames()
        state.isFlinging shouldBe true
        state.offset shouldNotBe Offset.Zero

        state.stopFling()

        state.isFlinging shouldBe false
        val stopped = state.offset
        advanceFrames()
        state.offset shouldBe stopped
        scope.cancel()
    }

    /** Смещение пальца с нарастающим временем: без него VelocityTracker даёт нулевую скорость. */
    private fun swipe() {
        repeat(SWIPE_STEPS) { step ->
            val position = Offset(START_X - step * STEP_PX, 0f)
            state.onPan(Offset(-STEP_PX, 0f), uptimeMillis = step * STEP_MILLIS, position = position)
        }
    }

    private fun TestScope.advanceFrames() = advanceTimeBy(FRAMES_PER_STEP * TestFrameClock.FRAME_MILLIS)

    // animateDecay живёт по кадрам, поэтому в контексте нужен свой MonotonicFrameClock.
    private fun TestScope.flingScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + TestFrameClock())

    private companion object {
        const val VIEWPORT = 1000f
        const val IMAGE = 4000f
        const val SWIPE_STEPS = 5
        const val STEP_PX = 40f
        const val STEP_MILLIS = 16L
        const val START_X = 800f
        const val FRAMES_PER_STEP = 3
    }
}
