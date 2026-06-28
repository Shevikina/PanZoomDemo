package com.shevikina.panzoomdemo.metrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val BYTES_PER_MB = 1_048_576L
private const val FPS_LOG_DELTA = 5
private const val RAM_LOG_DELTA_MB = 5L


/**
 * Оверлей для мониторинга производительности в реальном времени.
 *
 * FPS считается через [withFrameNanos]: подписывается на каждый vsync-сигнал
 * Choreographer и агрегирует кадры за секунду - в отличие от таймера, не создаёт
 * лишних пробуждений и не врёт при пропущенных кадрах.
 *
 * RAM отражает использованный heap JVM ([Runtime.totalMemory] − [Runtime.freeMemory]),
 * не учитывает нативную память (NDK, Skia-буферы) - для ориентира достаточно.
 */
@Composable
fun MetricsOverlay() {
    var fps by remember { mutableFloatStateOf(0f) }
    var memMb by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        var frames = 0
        var lastLoggedFps = -1
        var lastLoggedMem = -1L
        while (true) {
            withFrameNanos { now ->
                frames++
                val elapsed = (now - lastTime) / NANOS_PER_SECOND
                if (elapsed >= 1f) {
                    val newFps = (frames / elapsed).toInt()
                    val newMem = Runtime.getRuntime().run {
                        (totalMemory() - freeMemory()) / BYTES_PER_MB
                    }
                        lastLoggedFps = newFps
                        lastLoggedMem = newMem
                    fps = newFps.toFloat()
                    frames = 0
                    lastTime = now
                    memMb = newMem
                }
            }
        }
    }

    MetricsOverlayContent(
        fps = fps.toInt(), ram = memMb
    )
}


@Composable
private fun MetricsOverlayContent(
    fps: Int,
    ram: Long
) {
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(8.dp),
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text = "FPS: $fps  RAM: ${ram}MB",
                color = Color.Green,
                fontSize = 12.sp,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun MetricsOverlayContentPreview() {
    PanZoomDemoTheme {
        MetricsOverlayContent(fps = 60, ram = 90)
    }
}