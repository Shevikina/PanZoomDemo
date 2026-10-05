package com.shevikina.panzoomdemo.presentation.demo

import androidx.lifecycle.SavedStateHandle
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.domain.usecase.IPrepareImageSourceUseCase
import com.shevikina.panzoomdemo.presentation.demo.mapper.toUi
import com.shevikina.panzoomdemo.presentation.demo.model.ViewerUi
import com.shevikina.panzoomdemo.test.util.TestMainDispatcherRule
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test

class DemoBinderTest {

    @get:Rule
    val mainDispatcherRule = TestMainDispatcherRule()

    private val prepareImageSource = object : IPrepareImageSourceUseCase {
        override suspend fun invoke(asset: TestImageAsset): ImageSource =
            ImageSource(asset, path = "/cache/${asset.name}.jpg", sizeBytes = 1L)
    }

    private val middlewareFactory = object : DemoMiddleware.Factory {
        override fun create(scope: CoroutineScope): DemoMiddleware = DemoMiddleware(scope, prepareImageSource)
    }

    @Test
    fun `should restore user choices when binder is recreated`() {
        val handle = SavedStateHandle()

        val first = binder(handle)
        first.emit(DemoEvent.Input.ViewerModeChanged(ViewerMode.NAIVE))
        first.emit(DemoEvent.Input.AssetChanged(TestImageAsset.LARGE))
        first.emit(DemoEvent.Input.ToggleChanged(DemoToggle.LOD, checked = true))

        val restored = binder(handle).state.value

        restored.viewerMode shouldBe ViewerMode.NAIVE
        restored.selectedAsset shouldBe TestImageAsset.LARGE
        restored.checkedToggles shouldBe setOf(DemoToggle.LOD)
    }

    @Test
    fun `should start from naive mode on small asset when handle is empty`() {
        val state = binder(SavedStateHandle()).state.value

        state.viewerMode shouldBe ViewerMode.NAIVE
        state.selectedAsset shouldBe TestImageAsset.SMALL
        state.checkedToggles.shouldBeEmpty()
    }

    @Test
    fun `should prepare and display naive small after decode or draw failure`() {
        val cases = listOf(
            TestImageAsset.MEDIUM to ImageLoadError.from(RuntimeException("too large bitmap")),
            TestImageAsset.LARGE to ImageLoadError.from(IllegalStateException("Failed to decode")),
        )
        for ((asset, error) in cases) {
            val binder = binder(SavedStateHandle())
            binder.emit(DemoEvent.Input.AssetChanged(asset))

            binder.emit(DemoEvent.Internal.ImageLoadFailed(error))

            val state = binder.state.value
            state.viewerMode shouldBe ViewerMode.NAIVE
            state.selectedAsset shouldBe TestImageAsset.SMALL
            state.imageSource.dataOrNull?.asset shouldBe TestImageAsset.SMALL
            state.toUi().viewer shouldBe ViewerUi.Naive(TestImageAsset.SMALL)
            state.imageError shouldBe error
        }
    }

    private fun binder(handle: SavedStateHandle) = DemoBinder(DemoReducer(), middlewareFactory, handle)
}
