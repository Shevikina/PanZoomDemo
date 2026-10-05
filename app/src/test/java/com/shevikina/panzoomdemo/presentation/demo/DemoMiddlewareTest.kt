package com.shevikina.panzoomdemo.presentation.demo

import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.usecase.IPrepareImageSourceUseCase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class DemoMiddlewareTest {

    private val events = mutableListOf<DemoEvent>()

    @Test
    fun `should not report failure when preparation is cancelled by a new one`() = runTest {
        val scope = middlewareScope()
        val middleware = DemoMiddleware(scope, SlowPrepareImageSource())

        middleware.onCreate(DemoState(), ::record)
        middleware.process(
            DemoEvent.Input.AssetChanged(TestImageAsset.LARGE),
            DemoState(selectedAsset = TestImageAsset.LARGE),
            ::record,
        )
        advanceUntilIdle()
        scope.cancel()

        events.filterIsInstance<DemoEvent.Internal.ImagePrepareFailed>().shouldBeEmpty()
        events.count { it is DemoEvent.Internal.ImagePrepareStarted } shouldBe 2
        events.filterIsInstance<DemoEvent.Internal.ImageSourcePrepared>()
            .map { it.source.asset } shouldBe listOf(TestImageAsset.LARGE)
    }

    @Test
    fun `should report failure with its asset when preparation throws`() = runTest {
        val scope = middlewareScope()
        val middleware = DemoMiddleware(scope, FailingPrepareImageSource())

        middleware.onCreate(DemoState(selectedAsset = TestImageAsset.SMALL), ::record)
        advanceUntilIdle()
        scope.cancel()

        val failure = events.filterIsInstance<DemoEvent.Internal.ImagePrepareFailed>().single()
        failure.asset shouldBe TestImageAsset.SMALL
        failure.cause shouldBe FailingPrepareImageSource.CAUSE
    }

    // Отдельный скоуп, а не backgroundScope: фоновые задачи runTest не прокручиваются
    // advanceUntilIdle, и проверить порядок отмены на них нельзя.
    private fun TestScope.middlewareScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun record(event: DemoEvent) {
        events += event
    }

    private class SlowPrepareImageSource : IPrepareImageSourceUseCase {
        override suspend fun invoke(asset: TestImageAsset): ImageSource {
            delay(PREPARE_MILLIS)
            return ImageSource(asset, path = "/cache/${asset.name}.jpg", sizeBytes = 1L)
        }

        private companion object {
            const val PREPARE_MILLIS = 100L
        }
    }

    private class FailingPrepareImageSource : IPrepareImageSourceUseCase {
        override suspend fun invoke(asset: TestImageAsset): ImageSource = throw CAUSE

        companion object {
            val CAUSE = IOException("нет файла")
        }
    }
}
