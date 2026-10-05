package com.shevikina.panzoomdemo.data.repository

import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import com.shevikina.panzoomdemo.data.datasource.IJankLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.IMemoryLocalDataSource
import com.shevikina.panzoomdemo.domain.model.DeviceMetrics
import com.shevikina.panzoomdemo.domain.repository.IMetricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Метрики процесса раз в секунду.
 *
 * Без `@Singleton`, потому что поток холодный и каждый коллектор получает свой тикер.
 */
internal class MetricsRepository @Inject constructor(
    private val memory: IMemoryLocalDataSource,
    private val jank: IJankLocalDataSource,
    private val dispatchers: ICoroutineDispatchers,
) : IMetricsRepository {

    /**
     * Тикает раз в секунду.
     */
    override fun observeDeviceMetrics(): Flow<DeviceMetrics> = tickerFlow().flowOn(dispatchers.default)

    private fun tickerFlow(): Flow<DeviceMetrics> = flow {
        var tick = 0
        var pssMb = 0L
        while (true) {
            if (tick % PSS_REFRESH_TICKS == 0) pssMb = memory.pssMb()
            emit(
                DeviceMetrics(
                    javaHeapMb = memory.javaHeapMb(),
                    nativeHeapMb = memory.nativeHeapMb(),
                    pssMb = pssMb,
                    jankPerSecond = jank.flushAndGet(),
                ),
            )
            tick++
            delay(TICK)
        }
    }

    override fun recordJank() = jank.recordJank()

    private companion object {
        /** Период опроса метрик. */
        val TICK = 1.seconds

        const val PSS_REFRESH_TICKS = 2
    }
}
