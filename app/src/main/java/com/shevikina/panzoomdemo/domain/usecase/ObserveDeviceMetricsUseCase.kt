package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.DeviceMetrics
import com.shevikina.panzoomdemo.domain.repository.IMetricsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveDeviceMetricsUseCase @Inject constructor(
    private val repository: IMetricsRepository,
) : IObserveDeviceMetricsUseCase {

    override fun invoke(): Flow<DeviceMetrics> = repository.observeDeviceMetrics()
}
