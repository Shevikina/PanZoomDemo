package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.repository.IMetricsRepository
import javax.inject.Inject

class RecordJankUseCase @Inject constructor(
    private val repository: IMetricsRepository,
) : IRecordJankUseCase {

    override fun invoke() = repository.recordJank()
}
