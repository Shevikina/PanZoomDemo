package com.shevikina.panzoomdemo.presentation.metrics

import androidx.compose.runtime.Composable
import com.shevikina.panzoomdemo.presentation.metrics.content.MetricsOverlayCompact
import com.shevikina.panzoomdemo.presentation.metrics.content.MetricsOverlayFull
import com.shevikina.panzoomdemo.presentation.metrics.content.ReturnZone
import com.shevikina.panzoomdemo.presentation.metrics.effects.rememberDeviceMetricsEffect
import com.shevikina.panzoomdemo.presentation.metrics.effects.rememberFpsEffect

/**
 * Оверлей для мониторинга производительности в реальном времени.
 *
 * Частоту кадров считает сам, а память и джанк приходят потоком раз в секунду.
 *
 * Состояние держится внутри оверлея, а не в биндере экрана,
 * чтобы тик метрик не рекомпозировал весь экран.
 */
@Composable
fun MetricsOverlay(
    imageSizeBytes: Long,
    tileCounts: TileCountsState,
    showTiles: Boolean,
    visibility: MetricsOverlayVisibility = MetricsOverlayVisibility.FULL,
    onTap: () -> Unit = {},
) {
    val metrics = rememberDeviceMetricsEffect()
    val fps = rememberFpsEffect()

    when (visibility) {
        MetricsOverlayVisibility.HIDDEN -> ReturnZone(onTap = onTap)

        MetricsOverlayVisibility.COMPACT -> MetricsOverlayCompact(
            fps = fps,
            jank = metrics.jankPerSecond,
            onTap = onTap,
        )

        MetricsOverlayVisibility.FULL -> MetricsOverlayFull(
            imageFullSize = imageSizeBytes,
            fps = fps,
            ramJavaMb = metrics.javaHeapMb,
            ramNativeMb = metrics.nativeHeapMb,
            ramPssMb = metrics.pssMb,
            jank = metrics.jankPerSecond,
            tileCounts = tileCounts,
            showTiles = showTiles,
            onTap = onTap,
        )
    }
}
