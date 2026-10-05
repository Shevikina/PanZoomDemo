package com.shevikina.panzoomdemo.presentation.demo

import com.shevikina.panzoomdemo.core.mvi.RequestUI
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.metrics.MetricsOverlayVisibility
import io.kotest.matchers.shouldBe
import org.junit.Test

class DemoReducerTest {

    private val reducer = DemoReducer()

    @Test
    fun `should switch viewer mode`() {
        val state = reducer.reduce(DemoState(), DemoEvent.Input.ViewerModeChanged(ViewerMode.NAIVE))

        state.viewerMode shouldBe ViewerMode.NAIVE
    }

    @Test
    fun `should keep checked toggles when viewer mode changes`() {
        val initial = DemoState(checkedToggles = setOf(DemoToggle.LOD))

        val state = reducer.reduce(initial, DemoEvent.Input.ViewerModeChanged(ViewerMode.NAIVE))

        state.checkedToggles shouldBe setOf(DemoToggle.LOD)
    }

    @Test
    fun `should add and remove toggle`() {
        val checked = reducer.reduce(
            DemoState(),
            DemoEvent.Input.ToggleChanged(DemoToggle.CULLING, checked = true),
        )
        val unchecked = reducer.reduce(
            checked,
            DemoEvent.Input.ToggleChanged(DemoToggle.CULLING, checked = false),
        )

        checked.checkedToggles shouldBe setOf(DemoToggle.CULLING)
        unchecked.checkedToggles shouldBe emptySet()
    }

    @Test
    fun `should reset image source to loading when asset changes`() {
        val initial = DemoState(imageSource = RequestUI.Success(SOURCE))

        val state = reducer.reduce(initial, DemoEvent.Input.AssetChanged(TestImageAsset.SMALL))

        state.selectedAsset shouldBe TestImageAsset.SMALL
        state.imageSource shouldBe RequestUI.Loading
    }

    @Test
    fun `should accept prepared source when it matches selected asset`() {
        val initial = DemoState(selectedAsset = TestImageAsset.LARGE)

        val state = reducer.reduce(initial, DemoEvent.Internal.ImageSourcePrepared(SOURCE))

        state.imageSource shouldBe RequestUI.Success(SOURCE)
    }

    @Test
    fun `should ignore prepared source when user already switched asset`() {
        val initial = DemoState(selectedAsset = TestImageAsset.SMALL, imageSource = RequestUI.Loading)

        val state = reducer.reduce(initial, DemoEvent.Internal.ImageSourcePrepared(SOURCE))

        state.imageSource shouldBe RequestUI.Loading
    }

    @Test
    fun `should store image load error`() {
        val state = reducer.reduce(
            DemoState(),
            DemoEvent.Internal.ImageLoadFailed(OOM),
        )

        state.imageError shouldBe OOM
    }

    @Test
    fun `should classify prepare failure as image error`() {
        val state = reducer.reduce(
            DemoState(),
            DemoEvent.Internal.ImagePrepareFailed(DemoState().selectedAsset, OutOfMemoryError("no memory")),
        )

        state.imageError shouldBe ImageLoadError.OutOfMemory("no memory")
    }

    @Test
    fun `should ignore prepare failure when user already switched asset`() {
        val initial = DemoState(selectedAsset = TestImageAsset.SMALL)

        val state = reducer.reduce(
            initial,
            DemoEvent.Internal.ImagePrepareFailed(TestImageAsset.LARGE, OutOfMemoryError("no memory")),
        )

        state shouldBe initial
    }

    @Test
    fun `should clear error when asset changes`() {
        val failed = DemoState(imageError = OOM)

        val state = reducer.reduce(failed, DemoEvent.Input.AssetChanged(TestImageAsset.SMALL))

        state.imageError shouldBe null
    }

    @Test
    fun `should clear error when it is dismissed`() {
        val failed = DemoState(imageError = OOM)

        val state = reducer.reduce(failed, DemoEvent.Input.ErrorDismissed)

        state.imageError shouldBe null
    }

    @Test
    fun `should keep error when viewer mode changes`() {
        val failed = DemoState(imageError = OOM)

        val state = reducer.reduce(failed, DemoEvent.Input.ViewerModeChanged(ViewerMode.TILED))

        state.imageError shouldBe OOM
    }

    @Test
    fun `should fall back to naive small when a larger image load fails`() {
        for (asset in listOf(TestImageAsset.MEDIUM, TestImageAsset.LARGE)) {
            val naive = DemoState(
                viewerMode = ViewerMode.NAIVE,
                selectedAsset = asset,
                imageSource = RequestUI.Success(SOURCE.copy(asset = asset)),
            )

            val state = reducer.reduce(naive, DemoEvent.Internal.ImageLoadFailed(OOM))

            state.viewerMode shouldBe ViewerMode.NAIVE
            state.selectedAsset shouldBe TestImageAsset.SMALL
            state.imageSource shouldBe RequestUI.Loading
            state.imageError shouldBe OOM
        }
    }

    @Test
    fun `should not restart small when naive small fails`() {
        val naive = DemoState(imageSource = RequestUI.Success(SOURCE.copy(asset = TestImageAsset.SMALL)))

        val state = reducer.reduce(naive, DemoEvent.Internal.ImageLoadFailed(OOM))

        state shouldBe naive.copy(imageError = OOM)
    }

    @Test
    fun `should keep asset and mode when tiled load fails`() {
        val tiled = DemoState(viewerMode = ViewerMode.TILED, selectedAsset = TestImageAsset.LARGE)

        val state = reducer.reduce(tiled, DemoEvent.Internal.ImageLoadFailed(OOM))

        state shouldBe tiled.copy(imageError = OOM)
    }

    @Test
    fun `should ignore switch to naive while error is shown`() {
        val failed = DemoState(viewerMode = ViewerMode.TILED, imageError = OOM)

        val state = reducer.reduce(failed, DemoEvent.Input.ViewerModeChanged(ViewerMode.NAIVE))

        state shouldBe failed
    }

    @Test
    fun `should allow switch to naive after error is dismissed`() {
        val failed = DemoState(viewerMode = ViewerMode.TILED, imageError = OOM)

        val dismissed = reducer.reduce(failed, DemoEvent.Input.ErrorDismissed)
        val state = reducer.reduce(dismissed, DemoEvent.Input.ViewerModeChanged(ViewerMode.NAIVE))

        state.viewerMode shouldBe ViewerMode.NAIVE
    }

    @Test
    fun `should cycle overlay visibility on tap`() {
        val state = reducer.reduce(DemoState(), DemoEvent.Input.OverlayTapped)

        state.overlayVisibility shouldBe MetricsOverlayVisibility.COMPACT
    }

    private companion object {
        val OOM = ImageLoadError.OutOfMemory(message = "no memory")
        val SOURCE = ImageSource(TestImageAsset.LARGE, path = "/cache/large.jpg", sizeBytes = 42L)
    }
}
