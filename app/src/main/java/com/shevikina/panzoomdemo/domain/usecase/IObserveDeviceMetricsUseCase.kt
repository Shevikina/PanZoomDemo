package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.DeviceMetrics
import kotlinx.coroutines.flow.Flow

/** Поток метрик процесса: память и джанк-кадры. */
interface IObserveDeviceMetricsUseCase {

    operator fun invoke(): Flow<DeviceMetrics>
}
