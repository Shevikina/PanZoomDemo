package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.PanOffset
import io.kotest.matchers.shouldBe
import org.junit.Test

class CalculateViewportUseCaseTest {

    private val useCase = CalculateViewportUseCase()

    @Test
    fun `assert result at default scale and zero offset`() {
        val viewport = useCase(SCREEN_W, SCREEN_H, PanOffset.ZERO, scale = 1f)

        viewport shouldBe ImageRect(0f, 0f, SCREEN_W, SCREEN_H)
    }

    @Test
    fun `should shrink viewport in image coords when zoomed in`() {
        val viewport = useCase(SCREEN_W, SCREEN_H, PanOffset.ZERO, scale = 2f)

        viewport shouldBe ImageRect(0f, 0f, SCREEN_W / 2f, SCREEN_H / 2f)
    }

    @Test
    fun `should not return negative bounds when content is panned right`() {
        val viewport = useCase(SCREEN_W, SCREEN_H, PanOffset(100f, 50f), scale = 1f)

        viewport shouldBe ImageRect(0f, 0f, SCREEN_W - 100f, SCREEN_H - 50f)
    }

    @Test
    fun `should offset viewport when content is panned left`() {
        val viewport = useCase(SCREEN_W, SCREEN_H, PanOffset(-200f, -100f), scale = 1f)

        viewport shouldBe ImageRect(200f, 100f, SCREEN_W + 200f, SCREEN_H + 100f)
    }

    @Test
    fun `should return empty rect when scale is not positive`() {
        val viewport = useCase(SCREEN_W, SCREEN_H, PanOffset.ZERO, scale = 0f)

        viewport shouldBe ImageRect.EMPTY
    }

    private companion object {
        const val SCREEN_W = 1080f
        const val SCREEN_H = 1920f
    }
}
