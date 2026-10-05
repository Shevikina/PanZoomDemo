package com.shevikina.panzoomdemo.presentation.metrics.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import com.shevikina.panzoomdemo.presentation.metrics.color.fpsColor
import com.shevikina.panzoomdemo.presentation.metrics.color.jankColor
import com.shevikina.panzoomdemo.presentation.metrics.color.javaHeapColor
import com.shevikina.panzoomdemo.presentation.metrics.color.nativeHeapColor
import com.shevikina.panzoomdemo.presentation.metrics.color.pssColor
import com.shevikina.panzoomdemo.presentation.metrics.utils.appendLabel
import com.shevikina.panzoomdemo.presentation.metrics.utils.appendMetric

/**
 * Столбец с частотой кадров, джанком и памятью.
 *
 * Первая строка подписана частотой колбэков Choreographer, потому что система
 * выдаёт кадр и в том случае, если на экране ничего не поменялось.
 * Смотреть надо на соседнюю строку с джанком.
 *
 * Три числа памяти в одной строке красятся каждое само по себе.
 */
@Composable
internal fun PerformanceMetricsColumn(
    fps: Int,
    jank: Int,
    ramJavaMb: Long,
    ramNativeMb: Long,
    ramPssMb: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(METRICS_ITEM_SPACING),
    ) {
        MetricsOverlayItem(
            text = buildAnnotatedString {
                appendLabel("frames/s (Choreographer): ")
                appendMetric("$fps", fpsColor(fps))
            },
        )
        MetricsOverlayItem(
            text = buildAnnotatedString {
                appendLabel("Jank: ")
                appendMetric("$jank/s", jankColor(jank))
            },
        )
        MetricsOverlayItem(
            text = buildAnnotatedString {
                appendLabel("RAM ")
                appendMetric("J:$ramJavaMb", javaHeapColor(ramJavaMb))
                appendLabel(" ")
                appendMetric("N:$ramNativeMb", nativeHeapColor(ramNativeMb))
                appendLabel(" ")
                appendMetric("PSS:$ramPssMb", pssColor(ramPssMb))
                appendLabel(" MB")
            },
        )
    }
}
