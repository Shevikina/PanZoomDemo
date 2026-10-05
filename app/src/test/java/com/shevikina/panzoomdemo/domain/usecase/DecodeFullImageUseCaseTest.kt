package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.repository.IImageSourceRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DecodeFullImageUseCaseTest {

    private val image = mockk<DecodedImage>()
    private val repository = mockk<IImageSourceRepository>()
    private val useCase = DecodeFullImageUseCase(repository)

    @Test
    fun `assert result`() = runTest {
        coEvery { repository.decodeFullImage(TestImageAsset.MEDIUM) } returns image

        useCase(TestImageAsset.MEDIUM) shouldBe image
    }
}
