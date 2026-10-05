package com.shevikina.panzoomdemo.presentation.demo

import androidx.lifecycle.SavedStateHandle
import com.shevikina.panzoomdemo.core.mvi.RequestUI
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import io.kotest.matchers.shouldBe
import org.junit.Test

class DemoStateSaverTest {

    private val handle = SavedStateHandle()

    @Test
    fun `should restore user choices`() {
        val state = DemoState(
            viewerMode = ViewerMode.NAIVE,
            selectedAsset = TestImageAsset.LARGE,
            checkedToggles = setOf(DemoToggle.CULLING, DemoToggle.LOD),
        )

        DemoStateSaver.save(handle, state)
        val restored = DemoStateSaver.restore(handle)

        restored.viewerMode shouldBe ViewerMode.NAIVE
        restored.selectedAsset shouldBe TestImageAsset.LARGE
        restored.checkedToggles shouldBe setOf(DemoToggle.CULLING, DemoToggle.LOD)
    }

    @Test
    fun `should not restore transient fields`() {
        val state = DemoState(imageError = ImageLoadError.OutOfMemory("no memory"))

        DemoStateSaver.save(handle, state)
        val restored = DemoStateSaver.restore(handle)

        restored.imageError shouldBe null
        restored.imageSource shouldBe RequestUI.Loading
    }

    @Test
    fun `should fall back to defaults on empty handle`() {
        DemoStateSaver.restore(handle) shouldBe DemoState()
    }

    @Test
    fun `should ignore unknown values saved by older build`() {
        handle["viewer_mode"] = "SOMETHING_REMOVED"
        handle["checked_toggles"] = arrayListOf("GONE_TOGGLE")

        val restored = DemoStateSaver.restore(handle)

        restored.viewerMode shouldBe DemoState().viewerMode
        restored.checkedToggles shouldBe emptySet()
    }
}
