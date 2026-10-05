package com.shevikina.panzoomdemo.presentation.metrics.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.presentation.metrics.color.fpsColor
import com.shevikina.panzoomdemo.presentation.metrics.color.jankColor
import com.shevikina.panzoomdemo.presentation.metrics.elements.MetricsOverlayItem
import com.shevikina.panzoomdemo.presentation.metrics.utils.appendLabel
import com.shevikina.panzoomdemo.presentation.metrics.utils.appendMetric
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme

@Composable
internal fun MetricsOverlayCompact(
    fps: Int,
    jank: Int,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(8.dp),
    ) {
        MetricsOverlayItem(
            // Считаем колбэки Choreographer, а не перерисовки.
            text = buildAnnotatedString {
                appendLabel("frames/s: ")
                appendMetric("$fps", fpsColor(fps))
                appendLabel("  Jank: ")
                appendMetric("$jank/s", jankColor(jank))
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable(onClick = onTap),
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun MetricsOverlayCompactPreview() {
    PanZoomDemoTheme {
        MetricsOverlayCompact(
            fps = 42,
            jank = 2,
            onTap = {},
        )
    }
}
