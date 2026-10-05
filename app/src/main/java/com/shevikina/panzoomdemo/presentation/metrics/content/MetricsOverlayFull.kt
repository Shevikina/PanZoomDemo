package com.shevikina.panzoomdemo.presentation.metrics.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.presentation.metrics.TileCountsState
import com.shevikina.panzoomdemo.presentation.metrics.elements.ImageMetricsColumn
import com.shevikina.panzoomdemo.presentation.metrics.elements.PerformanceMetricsColumn
import com.shevikina.panzoomdemo.presentation.metrics.utils.BYTES_PER_MB
import com.shevikina.panzoomdemo.presentation.metrics.utils.formatImageSize
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme

private val METRICS_OVERLAY_PADDING = 8.dp

/** Развёрнутая плашка метрик */
@Composable
internal fun MetricsOverlayFull(
    imageFullSize: Long,
    fps: Int,
    ramJavaMb: Long,
    ramNativeMb: Long,
    ramPssMb: Long,
    jank: Int,
    tileCounts: TileCountsState,
    showTiles: Boolean,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(METRICS_OVERLAY_PADDING),
    ) {
        PerformanceMetricsColumn(
            fps = fps,
            jank = jank,
            ramJavaMb = ramJavaMb,
            ramNativeMb = ramNativeMb,
            ramPssMb = ramPssMb,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable(onClick = onTap),
        )
        ImageMetricsColumn(
            imageSize = formatImageSize(imageFullSize),
            tileCounts = tileCounts,
            showTiles = showTiles,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun MetricsOverlayFullPreview() {
    PanZoomDemoTheme {
        MetricsOverlayFull(
            imageFullSize = 8 * BYTES_PER_MB,
            fps = 60,
            ramJavaMb = 8,
            ramNativeMb = 187,
            ramPssMb = 245,
            jank = 3,
            tileCounts = TileCountsState(held = 11, needed = 12),
            showTiles = true,
            onTap = {},
        )
    }
}
