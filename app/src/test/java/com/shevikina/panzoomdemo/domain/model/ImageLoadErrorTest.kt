package com.shevikina.panzoomdemo.domain.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class ImageLoadErrorTest {

    @Test
    fun `assert result when bitmap did not fit in memory`() {
        val error = ImageLoadError.from(OutOfMemoryError("Failed to allocate 4000000 bytes"))

        error.shouldBeInstanceOf<ImageLoadError.OutOfMemory>()
        error.type shouldBe "OutOfMemoryError"
        error.message shouldBe "Failed to allocate 4000000 bytes"
    }

    @Test
    fun `should keep type and message of platform draw rejection`() {
        val error =
            ImageLoadError.from(RuntimeException("Canvas: trying to draw too large(256000000bytes) bitmap."))

        error.shouldBeInstanceOf<ImageLoadError.Unknown>()
        error.type shouldBe "RuntimeException"
        error.message shouldBe "Canvas: trying to draw too large(256000000bytes) bitmap."
    }

    @Test
    fun `should survive failure without message`() {
        val error = ImageLoadError.from(IllegalStateException())

        error.type shouldBe "IllegalStateException"
        error.message shouldBe null
    }
}
