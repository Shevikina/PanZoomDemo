package com.shevikina.panzoomdemo.domain.repository

import com.shevikina.panzoomdemo.domain.model.DeviceMetrics
import kotlinx.coroutines.flow.Flow

/** Метрики процесса: память и джанк-кадры. */
interface IMetricsRepository {

    /** Поток снимков метрик */
    fun observeDeviceMetrics(): Flow<DeviceMetrics>

    /** Запись джанк-кадра */
    fun recordJank()
}
