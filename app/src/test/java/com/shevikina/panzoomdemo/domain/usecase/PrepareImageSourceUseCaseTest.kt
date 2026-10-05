package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.repository.IImageSourceRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrepareImageSourceUseCaseTest {

    private val repository = mockk<IImageSourceRepository>()
    private val useCase = PrepareImageSourceUseCase(repository)

    @Test
    fun `assert result`() = runTest {
        coEvery { repository.prepareTiledSource(ASSET) } returns SOURCE

        useCase(ASSET) shouldBe SOURCE
    }

    @Test
    fun `assert invocation`() = runTest {
        coEvery { repository.prepareTiledSource(any()) } returns SOURCE

        useCase(ASSET)

        coVerify(exactly = 1) { repository.prepareTiledSource(ASSET) }
    }

    private companion object {
        val ASSET = TestImageAsset.LARGE
        val SOURCE = ImageSource(ASSET, path = "/cache/large.jpg", sizeBytes = 1024L)
    }
}
