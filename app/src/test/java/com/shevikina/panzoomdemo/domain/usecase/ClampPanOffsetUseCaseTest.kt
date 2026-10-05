package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.PanBounds
import com.shevikina.panzoomdemo.domain.model.PanOffset
import io.kotest.matchers.shouldBe
import org.junit.Test

class ClampPanOffsetUseCaseTest {

    private val useCase = ClampPanOffsetUseCase()

    @Test
    fun `should not clamp when bounds are unspecified`() {
        val offset = PanOffset(9999f, -9999f)

        useCase(offset, scale = 1f, bounds = PanBounds.UNSPECIFIED) shouldBe offset
    }

    @Test
    fun `should keep offset inside bounds when content is larger than viewport`() {
        val bounds = PanBounds(VIEWPORT, VIEWPORT, IMAGE, IMAGE)

        useCase(PanOffset(500f, 500f), scale = 1f, bounds = bounds) shouldBe PanOffset.ZERO
        useCase(PanOffset(-5000f, -5000f), scale = 1f, bounds = bounds) shouldBe
            PanOffset(VIEWPORT - IMAGE, VIEWPORT - IMAGE)
    }

    @Test
    fun `should pin offset to zero when scaled content matches viewport`() {
        val bounds = PanBounds(VIEWPORT, VIEWPORT, VIEWPORT, VIEWPORT)

        useCase(PanOffset(100f, -100f), scale = 1f, bounds = bounds) shouldBe PanOffset.ZERO
    }

    @Test
    fun `should account for scale when content is zoomed out`() {
        val bounds = PanBounds(VIEWPORT, VIEWPORT, IMAGE, IMAGE)

        // При scale=0.1 картинка 2000 px превращается в 200 px и становится уже viewport.
        useCase(PanOffset(9999f, 9999f), scale = 0.1f, bounds = bounds) shouldBe
            PanOffset(VIEWPORT - IMAGE * 0.1f, VIEWPORT - IMAGE * 0.1f)
    }

    private companion object {
        const val VIEWPORT = 1000f
        const val IMAGE = 2000f
    }
}
