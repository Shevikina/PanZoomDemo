package com.shevikina.panzoomdemo.metrics

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.metrics.elements.MetricsOverlayItem
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val BYTES_PER_MB = 1_048_576L
private const val BYTES_PER_KB = 1_024L

private const val TAG = "PanZoomDemo"
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
fun MetricsOverlay(fileName: String) {
    val context = LocalContext.current
    val imageSize = remember(fileName) {
        runCatching { context.assets.openFd(fileName).use { it.length } }
            .onSuccess { Log.d(TAG, "Image $fileName is open. Size: $it bytes") }
            .onFailure { Log.e(TAG, "Image $fileName is NOT open. Error: ${it.message}") }
            .getOrDefault(-1L)
    }

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
                    if (kotlin.math.abs(newFps - lastLoggedFps) >= FPS_LOG_DELTA) {
                        Log.d(TAG, "FPS: $newFps")
                        lastLoggedFps = newFps
                    }
                    if (kotlin.math.abs(newMem - lastLoggedMem) >= RAM_LOG_DELTA_MB) {
                        Log.d(TAG, "RAM: ${newMem}MB")
                        lastLoggedMem = newMem
                    }
                    fps = newFps.toFloat()
                    frames = 0
                    lastTime = now
                    memMb = newMem
                }
            }
        }
    }

    MetricsOverlayContent(
        imageFullSize = imageSize,
        fps = fps.toInt(),
        ram = memMb
    )
}


@Composable
private fun MetricsOverlayContent(
    imageFullSize: Long,
    fps: Int,
    ram: Long
) {
    val imageSizeMb = imageFullSize / BYTES_PER_MB
    val imageSizeKb = imageFullSize / BYTES_PER_KB

    val imageSizeStr = when {
        imageSizeMb > 0 -> "${imageSizeMb}Mb"
        imageSizeKb > 0 -> "${imageSizeKb}Kb"
        else -> "${imageFullSize}bytes"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(8.dp)
    ) {
        MetricsOverlayItem(
            text = "FPS: $fps  RAM: ${ram}MB",
            modifier = Modifier.align(Alignment.TopEnd)
        )
        MetricsOverlayItem(
            text = "Full Image: $imageSizeStr",
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun MetricsOverlayContentPreview() {
    PanZoomDemoTheme {
        MetricsOverlayContent(
            imageFullSize = 8 * BYTES_PER_MB,
            fps = 60,
            ram = 90
        )
    }
}