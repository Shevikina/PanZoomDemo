package com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive

import androidx.compose.ui.geometry.Offset
import com.shevikina.panzoomdemo.domain.usecase.ClampPanOffsetUseCase
import com.shevikina.panzoomdemo.test.stub.FakeDecodedImage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NaiveImageViewerStateTest {

    private val state = NaiveImageViewerState(
        decodedImage = FakeDecodedImage(width = IMAGE_W, height = IMAGE_H),
        clampPanOffset = ClampPanOffsetUseCase(),
    )

    @Test
    fun `should clamp by fitted size, not by original`() {
        state.onViewportChanged(VIEWPORT, VIEWPORT)

        state.panZoom.onPan(Offset(-400f, 400f), uptimeMillis = 0L, position = Offset.Zero)

        // Вписанный размер 500x250, поэтому по ширине запаса нет, а по высоте он равен 250.
        // По исходным 1000x500 вышло бы наоборот, Offset(-400, 0).
        state.panZoom.offset shouldBe Offset(0f, 250f)
    }

    @Test
    fun `should raise draw rejected flag when failure is taken`() = runTest {
        val cause = RuntimeException("too large")
        state.isDrawRejected shouldBe false
        state.reportDrawFailure(cause)

        state.awaitDrawFailure() shouldBe cause

        state.isDrawRejected shouldBe true
    }

    @Test
    fun `should keep only the last failure of a burst`() = runTest {
        val last = RuntimeException("last")
        state.reportDrawFailure(RuntimeException("first"))
        state.reportDrawFailure(last)

        state.awaitDrawFailure() shouldBe last
    }

    private companion object {
        const val IMAGE_W = 1000
        const val IMAGE_H = 500
        const val VIEWPORT = 500f
    }
}
