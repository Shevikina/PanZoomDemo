package com.shevikina.panzoomdemo.presentation.demo.elements.controlpanel

import io.kotest.matchers.shouldBe
import org.junit.Test

class DemoControlPanelUtilsTest {

    @Test
    fun `assert result when some optimizations are on`() {
        activeOptionsSummary(listOf("Culling", "LOD"), NONE) shouldBe "Culling, LOD"
    }

    @Test
    fun `should report empty list when nothing is enabled`() {
        activeOptionsSummary(emptyList(), NONE) shouldBe NONE
    }

    private companion object {
        const val NONE = "нет"
    }
}
