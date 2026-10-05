package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.repository.IMetricsRepository
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class RecordJankUseCaseTest {

    private val repository = mockk<IMetricsRepository>(relaxed = true)
    private val useCase = RecordJankUseCase(repository)

    @Test
    fun `assert invocation`() {
        useCase()

        verify(exactly = 1) { repository.recordJank() }
    }
}
