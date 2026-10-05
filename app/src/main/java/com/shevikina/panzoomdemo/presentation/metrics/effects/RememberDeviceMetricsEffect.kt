package com.shevikina.panzoomdemo.presentation.metrics.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.DeviceMetrics
import com.shevikina.panzoomdemo.presentation.di.rememberPresentationEntryPoint
import kotlin.math.abs

private const val RAM_LOG_DELTA_MB = 5L

/**
 * Отдаёт метрики процесса, обновляемые раз в секунду.
 *
 * В превью возвращает пустые значения
 *
 * Сбор идёт в одной корутине на всё время жизни оверлея,
 * чтобы логирование не пересоздавалось каждую секунду в цикле, который сам же измеряет джанк.
 */
@Composable
internal fun rememberDeviceMetricsEffect(): DeviceMetrics {
    if (LocalInspectionMode.current) return DeviceMetrics.EMPTY

    val entryPoint = rememberPresentationEntryPoint()
    val observeDeviceMetrics = remember(entryPoint) { entryPoint.observeDeviceMetricsUseCase() }
    val metricsFlow = remember(observeDeviceMetrics) { observeDeviceMetrics() }
    var metrics by remember { mutableStateOf(DeviceMetrics.EMPTY) }

    LaunchedEffect(metricsFlow) {
        var lastJavaMb = -1L
        var lastNativeMb = -1L
        var lastPssMb = -1L
        metricsFlow.collect { snapshot ->
            metrics = snapshot
            if (abs(snapshot.javaHeapMb - lastJavaMb) >= RAM_LOG_DELTA_MB) {
                DemoLog.analytics { "RAM J: ${snapshot.javaHeapMb}MB" }
                lastJavaMb = snapshot.javaHeapMb
            }
            if (abs(snapshot.nativeHeapMb - lastNativeMb) >= RAM_LOG_DELTA_MB) {
                DemoLog.analytics { "RAM N: ${snapshot.nativeHeapMb}MB" }
                lastNativeMb = snapshot.nativeHeapMb
            }
            if (abs(snapshot.pssMb - lastPssMb) >= RAM_LOG_DELTA_MB) {
                DemoLog.analytics { "RAM PSS: ${snapshot.pssMb}MB" }
                lastPssMb = snapshot.pssMb
            }
            if (snapshot.jankPerSecond > 0) DemoLog.warn("Jank frames/s: ${snapshot.jankPerSecond}")
        }
    }
    return metrics
}
