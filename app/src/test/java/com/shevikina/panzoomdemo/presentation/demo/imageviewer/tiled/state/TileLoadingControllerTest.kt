package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import com.shevikina.panzoomdemo.domain.model.DecodedTile
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.test.stub.FakeDecodedImage
import com.shevikina.panzoomdemo.test.stub.FakeTileSource
import com.shevikina.panzoomdemo.test.util.TestCoroutineDispatchers
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Везде StandardTestDispatcher, а не дефолтный Unconfined: декод здесь запускается с main,
 * а заканчивается на io, и порядок этих шагов - как раз то, что проверяется. На Unconfined
 * задача выполняется прямо внутри launch, и половина проверок стала бы бессмысленной.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileLoadingControllerTest {

    private val decodedImages = mutableMapOf<TileRect, FakeDecodedImage>()

    private val tileSource = FakeTileSource { rect, sampleSize -> decoded(rect, sampleSize) }

    @Test
    fun `should decode queued tiles that are still needed`() = runTest {
        val scope = decodeScope()
        val controller = controller()
        val needed = setOf(RECT_A, RECT_B)
        controller.enqueue(needed)

        val launched = controller.launchUpToLimit(scope, needed)
        advanceUntilIdle()

        launched shouldBe 2
        controller.pendingCount shouldBe 2
        controller.queuedCount shouldBe 0
        scope.cancel()
    }

    @Test
    fun `should skip tiles that left viewport before launch`() = runTest {
        val scope = decodeScope()
        val controller = controller()
        controller.enqueue(setOf(RECT_A, RECT_B))

        val launched = controller.launchUpToLimit(scope, needed = setOf(RECT_A))
        advanceUntilIdle()

        launched shouldBe 1
        controller.pendingCount shouldBe 1
        scope.cancel()
    }

    @Test
    fun `assert invocation when tile is tracked`() = runTest {
        val controller = controller()
        controller.enqueue(setOf(RECT_A))

        controller.isTracked(RECT_A) shouldBe true
        controller.isTracked(RECT_B) shouldBe false
    }

    @Test
    fun `should return buffers of tiles that left viewport`() = runTest {
        val scope = decodeScope()
        val controller = controller()
        val needed = setOf(RECT_A, RECT_B)
        controller.enqueue(needed)
        controller.launchUpToLimit(scope, needed)
        advanceUntilIdle()

        controller.cullOutOfViewport(setOf(RECT_A)) shouldBe true

        decodedImages.getValue(RECT_B).releaseCount shouldBe 1
        controller.pendingCount shouldBe 1
        scope.cancel()
    }

    @Test
    fun `should return buffers when lod generation is cancelled`() = runTest {
        val scope = decodeScope()
        val controller = controller()
        val needed = setOf(RECT_A, RECT_B)
        controller.enqueue(needed)
        controller.launchUpToLimit(scope, needed)
        advanceUntilIdle()

        val discarded = controller.cancelGeneration()

        discarded shouldBe 2
        decodedImages.values.all { it.releaseCount == 1 } shouldBe true
        controller.pendingCount shouldBe 0
        controller.queuedCount shouldBe 0
        scope.cancel()
    }

    @Test
    fun `should move decoded tiles into cache`() = runTest {
        val scope = decodeScope()
        val controller = controller()
        val cache = TileCache()
        val needed = setOf(RECT_A)
        controller.enqueue(needed)
        controller.launchUpToLimit(scope, needed)
        advanceUntilIdle()

        val flushed = controller.flushPendingInto(cache)

        flushed shouldBe 1
        cache.activeTiles.keys shouldBe setOf(RECT_A)
        controller.pendingCount shouldBe 0
        scope.cancel()
    }

    @Test
    fun `should skip decode when lod changed while task waited in queue`() = runTest {
        val scope = decodeScope()
        val controller = controller()
        controller.activeSampleSize = 1
        controller.enqueue(setOf(RECT_A))

        controller.launchUpToLimit(scope, setOf(RECT_A))
        // Задача уже создана, но ещё не выполнялась: ровно тот зазор, в котором на устройстве
        // успевает смениться уровень детализации.
        controller.activeSampleSize = 2
        advanceUntilIdle()

        controller.pendingCount shouldBe 0
        controller.isTracked(RECT_A) shouldBe false
        scope.cancel()
    }

    @Test
    fun `should stop tracking tile when decode returned nothing`() = runTest {
        val scope = decodeScope()
        val empty = controller(FakeTileSource { _, _ -> null })
        empty.enqueue(setOf(RECT_A))
        empty.launchUpToLimit(scope, setOf(RECT_A))
        advanceUntilIdle()

        empty.isTracked(RECT_A) shouldBe false
        empty.loadingCount shouldBe 0

        empty.enqueue(setOf(RECT_A))
        empty.launchUpToLimit(scope, setOf(RECT_A)) shouldBe 1
        scope.cancel()
    }

    @Test
    fun `should stop tracking cancelled tile`() = runTest {
        val scope = decodeScope()
        val delayed = controller(
            FakeTileSource { rect, sampleSize ->
                delay(DECODE_MILLIS)
                decoded(rect, sampleSize)
            },
        )
        delayed.enqueue(setOf(RECT_A))
        delayed.launchUpToLimit(scope, setOf(RECT_A))
        runCurrent()

        scope.cancel()
        advanceUntilIdle()

        delayed.loadingCount shouldBe 0
        delayed.isTracked(RECT_A) shouldBe false
    }

    @Test
    fun `should keep newer generation job when older one finishes`() = runTest {
        val scope = decodeScope()
        lateinit var raced: TileLoadingController
        var firstDecode = true
        // Смена генерации происходит ровно во время декода первой задачи: так на устройстве
        // и выходит, когда LOD меняется под уже летящим декодом. Вторая задача остаётся
        // висеть в декоде, иначе она успела бы сняться сама.
        val racingSource = FakeTileSource { rect, sampleSize ->
            if (firstDecode) {
                firstDecode = false
                raced.restartGeneration(scope)
            } else {
                delay(DECODE_MILLIS)
            }
            decoded(rect, sampleSize)
        }
        raced = controller(racingSource)
        raced.enqueue(setOf(RECT_A))
        raced.launchUpToLimit(scope, setOf(RECT_A))

        runCurrent()

        raced.loadingCount shouldBe 1
        raced.isTracked(RECT_A) shouldBe true
        scope.cancel()
    }

    @Test
    fun `should report failure and untrack tile when decode runs out of memory`() = runTest {
        val scope = decodeScope()
        val failures = mutableListOf<Throwable>()
        val outOfMemory = OutOfMemoryError("Failed to allocate a 1048576 byte allocation")
        val controller = controller(
            tileSource = FakeTileSource { _, _ -> throw outOfMemory },
            onDecodeFailed = failures::add,
        )
        controller.enqueue(setOf(RECT_A))

        controller.launchUpToLimit(scope, setOf(RECT_A))
        advanceUntilIdle()

        failures shouldBe listOf(outOfMemory)
        // Запись снимается в finally: без этого тайл навсегда остался бы «в загрузке».
        controller.isTracked(RECT_A) shouldBe false
        controller.loadingCount shouldBe 0
        controller.pendingCount shouldBe 0
        scope.cancel()
    }

    @Test
    fun `should stop decoding when controller is disposed`() = runTest {
        val scope = decodeScope()
        val decodedRects = mutableListOf<TileRect>()
        val controller = controller(
            tileSource = FakeTileSource { rect, sampleSize ->
                delay(DECODE_MILLIS)
                decodedRects += rect
                decoded(rect, sampleSize)
            },
        )
        val needed = setOf(RECT_A, RECT_B)
        controller.enqueue(needed)
        controller.launchUpToLimit(scope, needed)
        runCurrent()

        controller.dispose()
        advanceUntilIdle()

        // Ни один тайл не доехал до конца декода, значит смена ассета не оставляет
        // загрузку прежнего изображения работать в фоне.
        decodedRects.shouldBeEmpty()
        controller.loadingCount shouldBe 0
        controller.pendingCount shouldBe 0
        controller.queuedCount shouldBe 0
        scope.cancel()
    }

    /** Задача первой генерации снята, вместо неё запущена вторая для того же тайла. */
    private fun TileLoadingController.restartGeneration(scope: CoroutineScope) {
        cancelGeneration()
        enqueue(setOf(RECT_A))
        launchUpToLimit(scope, setOf(RECT_A))
    }

    private fun TestScope.controller(
        tileSource: FakeTileSource = this@TileLoadingControllerTest.tileSource,
        onDecodeFailed: (Throwable) -> Unit = {},
    ) = TileLoadingController(
        tileSource = tileSource,
        dispatchers = TestCoroutineDispatchers(StandardTestDispatcher(testScheduler)),
        onDecodeFailed = onDecodeFailed,
    )

    // Отдельный скоуп, а не backgroundScope: фоновые задачи runTest не прокручиваются
    // advanceUntilIdle, и порядок исполнения на них не проверить.
    private fun TestScope.decodeScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun decoded(
        rect: TileRect,
        sampleSize: Int,
    ): DecodedTile {
        val image = FakeDecodedImage()
        decodedImages[rect] = image
        return DecodedTile(rect, sampleSize, image)
    }

    private companion object {
        const val DECODE_MILLIS = 100L
        val RECT_A = TileRect(0, 0, 512, 512)
        val RECT_B = TileRect(512, 0, 1024, 512)
    }
}
