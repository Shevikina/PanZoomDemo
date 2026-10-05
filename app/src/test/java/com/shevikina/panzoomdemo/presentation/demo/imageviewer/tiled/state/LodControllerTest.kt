package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import com.shevikina.panzoomdemo.domain.model.SampleSize
import com.shevikina.panzoomdemo.domain.usecase.DebounceLodUseCase
import com.shevikina.panzoomdemo.domain.usecase.ResolveSampleSizeUseCase
import io.kotest.matchers.shouldBe
import org.junit.Test

class LodControllerTest {

    private val controller = LodController(ResolveSampleSizeUseCase(), DebounceLodUseCase())

    @Test
    fun `should report change only after debounce is satisfied`() {
        val reports = List(SampleSize.STABLE_INVOCATIONS) { controller.update(ZOOMED_OUT, lodEnabled = true) }

        reports.dropLast(1).all { it } shouldBe false
        reports.last() shouldBe true
        controller.sampleSize shouldBe SampleSize.HALF
    }

    @Test
    fun `should not report change while scale stays in the same band`() {
        repeat(SampleSize.STABLE_INVOCATIONS) { controller.update(ZOOMED_OUT, lodEnabled = true) }

        controller.update(ZOOMED_OUT, lodEnabled = true) shouldBe false
        controller.sampleSize shouldBe SampleSize.HALF
    }

    @Test
    fun `should stay at full resolution when lod is disabled`() {
        repeat(SampleSize.STABLE_INVOCATIONS * 2) { controller.update(ZOOMED_OUT, lodEnabled = false) }

        controller.sampleSize shouldBe SampleSize.MIN
    }

    private companion object {
        // Ниже порога перехода с полного разрешения на половинное.
        const val ZOOMED_OUT = 0.4f
    }
}
