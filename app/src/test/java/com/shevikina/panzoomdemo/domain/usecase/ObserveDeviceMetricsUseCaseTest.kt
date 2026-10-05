package com.shevikina.panzoomdemo.domain.usecase

import app.cash.turbine.test
import com.shevikina.panzoomdemo.domain.model.DeviceMetrics
import com.shevikina.panzoomdemo.domain.repository.IMetricsRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ObserveDeviceMetricsUseCaseTest {

    private val repository = mockk<IMetricsRepository>()
    private val useCase = ObserveDeviceMetricsUseCase(repository)

    @Test
    fun `assert result`() = runTest {
        every { repository.observeDeviceMetrics() } returns flowOf(METRICS)

        useCase().test {
            awaitItem() shouldBe METRICS
            awaitComplete()
        }
    }

    private companion object {
        val METRICS = DeviceMetrics(javaHeapMb = 8, nativeHeapMb = 187, pssMb = 245, jankPerSecond = 3)
    }
}
