package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.SampleSize
import io.kotest.matchers.shouldBe
import org.junit.Test

class ResolveSampleSizeUseCaseTest {

    private val useCase = ResolveSampleSizeUseCase()

    @Test
    fun `should always return full resolution when lod is disabled`() {
        useCase(scale = 0.01f, currentSampleSize = 8, lodEnabled = false) shouldBe SampleSize.MIN
    }

    @Test
    fun `should step down when scale drops below threshold`() {
        useCase(scale = 0.44f, currentSampleSize = 1, lodEnabled = true) shouldBe 2
        useCase(scale = 0.21f, currentSampleSize = 2, lodEnabled = true) shouldBe 4
        useCase(scale = 0.10f, currentSampleSize = 4, lodEnabled = true) shouldBe 8
    }

    @Test
    fun `should step up when scale rises above threshold`() {
        useCase(scale = 0.56f, currentSampleSize = 2, lodEnabled = true) shouldBe 1
        useCase(scale = 0.29f, currentSampleSize = 4, lodEnabled = true) shouldBe 2
        useCase(scale = 0.15f, currentSampleSize = 8, lodEnabled = true) shouldBe 4
    }

    @Test
    fun `assert, when {scale is inside hysteresis band, level stays the same}`() {
        useCase(scale = 0.50f, currentSampleSize = 2, lodEnabled = true) shouldBe 2
        useCase(scale = 0.25f, currentSampleSize = 4, lodEnabled = true) shouldBe 4
        useCase(scale = 0.125f, currentSampleSize = 8, lodEnabled = true) shouldBe 8
    }

    @Test
    fun `should not flip level back and forth around a threshold`() {
        val down = useCase(scale = 0.44f, currentSampleSize = 1, lodEnabled = true)
        val backUp = useCase(scale = 0.46f, currentSampleSize = down, lodEnabled = true)

        down shouldBe 2
        backUp shouldBe 2
    }
}
