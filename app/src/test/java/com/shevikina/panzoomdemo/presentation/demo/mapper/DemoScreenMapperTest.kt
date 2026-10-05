package com.shevikina.panzoomdemo.presentation.demo.mapper

import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.core.mvi.RequestUI
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.demo.DemoState
import com.shevikina.panzoomdemo.presentation.demo.model.ViewerUi
import io.kotest.matchers.shouldBe
import org.junit.Test

class DemoScreenMapperTest {

    @Test
    fun `assert result`() {
        val ui = DemoState(
            viewerMode = ViewerMode.TILED,
            selectedAsset = TestImageAsset.LARGE,
            checkedToggles = setOf(DemoToggle.CULLING),
            imageSource = RequestUI.Success(SOURCE),
        ).toUi()

        ui.viewerMode shouldBe ViewerMode.TILED
        ui.selectedAsset shouldBe TestImageAsset.LARGE
        ui.imageSource shouldBe SOURCE
        ui.isImageLoading shouldBe false
    }

    @Test
    fun `should map every toggle in declaration order`() {
        val ui = DemoState().toUi()

        ui.toggles.map { it.toggle } shouldBe DemoToggle.entries
        ui.toggles.first().titleRes shouldBe R.string.demo_culling_title
        ui.toggles.first().subtitleRes shouldBe R.string.demo_culling_subtitle
    }

    @Test
    fun `should mark checked toggles`() {
        val state = DemoState(viewerMode = ViewerMode.TILED, checkedToggles = setOf(DemoToggle.LOD))

        val ui = state.toUi()

        ui.toggles.single { it.toggle == DemoToggle.LOD }.checked shouldBe true
        ui.toggles.single { it.toggle == DemoToggle.CULLING }.checked shouldBe false
    }

    @Test
    fun `should disable toggles when viewer mode is naive`() {
        val ui = DemoState(viewerMode = ViewerMode.NAIVE).toUi()

        ui.toggles.all { it.enabled } shouldBe false
    }

    @Test
    fun `should show toggles unchecked when viewer mode is naive`() {
        val state = DemoState(
            viewerMode = ViewerMode.NAIVE,
            checkedToggles = setOf(DemoToggle.CULLING, DemoToggle.LOD),
        )

        state.toUi().toggles.none { it.checked } shouldBe true
    }

    @Test
    fun `should restore checked toggles when viewer mode returns to tiled`() {
        val checked = setOf(DemoToggle.CULLING, DemoToggle.LOD)
        val state = DemoState(viewerMode = ViewerMode.NAIVE, checkedToggles = checked)

        val ui = state.copy(viewerMode = ViewerMode.TILED).toUi()

        ui.toggles.filter { it.checked }.map { it.toggle }.toSet() shouldBe checked
    }

    @Test
    fun `assert, when {source is loading, image is null and loader is shown}`() {
        val ui = DemoState(imageSource = RequestUI.Loading).toUi()

        ui.imageSource shouldBe null
        ui.isImageLoading shouldBe true
    }

    @Test
    fun `assert, when {source failed, image is null and loader is hidden}`() {
        val ui = DemoState(imageSource = RequestUI.Error(IllegalStateException("boom"))).toUi()

        ui.imageSource shouldBe null
        ui.isImageLoading shouldBe false
    }

    @Test
    fun `should pass error type and message to ui`() {
        val ui = DemoState(imageError = ImageLoadError.OutOfMemory("no memory")).toUi()

        ui.error?.type shouldBe "OutOfMemoryError"
        ui.error?.message shouldBe "no memory"
    }

    @Test
    fun `should pass non oom failures to ui as well`() {
        val error = ImageLoadError.Unknown(type = "RuntimeException", message = "too large bitmap")

        val ui = DemoState(imageError = error).toUi()

        ui.error?.type shouldBe "RuntimeException"
        ui.error?.message shouldBe "too large bitmap"
    }

    @Test
    fun `assert, when {error is shown, loader is hidden}`() {
        val ui = DemoState(
            imageSource = RequestUI.Loading,
            imageError = ImageLoadError.OutOfMemory(message = null),
        ).toUi()

        ui.isImageLoading shouldBe false
    }

    @Test
    fun `should not report error when everything is fine`() {
        DemoState().toUi().error shouldBe null
    }

    @Test
    fun `should give naive viewer only the asset`() {
        val ui = DemoState(
            viewerMode = ViewerMode.NAIVE,
            checkedToggles = DemoToggle.entries.toSet(),
            imageSource = RequestUI.Success(SOURCE),
        ).toUi()

        ui.viewer shouldBe ViewerUi.Naive(SOURCE.asset)
    }

    @Test
    fun `should give tiled viewer every toggle`() {
        val ui = DemoState(
            viewerMode = ViewerMode.TILED,
            checkedToggles = setOf(DemoToggle.CULLING, DemoToggle.GRAPHICS_LAYER),
            imageSource = RequestUI.Success(SOURCE),
        ).toUi()

        ui.viewer shouldBe ViewerUi.Tiled(
            source = SOURCE,
            culling = true,
            lod = false,
            useGraphicsLayer = true,
        )
    }

    @Test
    fun `should not give a viewer while source is loading`() {
        DemoState(imageSource = RequestUI.Loading).toUi().viewer shouldBe null
    }

    @Test
    fun `should not give a viewer when source failed`() {
        val state = DemoState(imageSource = RequestUI.Error(IllegalStateException("boom")))

        state.toUi().viewer shouldBe null
    }

    private companion object {
        val SOURCE = ImageSource(TestImageAsset.LARGE, path = "/cache/large.jpg", sizeBytes = 42L)
    }
}
