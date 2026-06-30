package com.shevikina.panzoomdemo.metrics

import android.os.Debug
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val BYTES_PER_MB = 1_048_576L
private const val BYTES_PER_KB = 1_024L

private const val TAG = "PanZoomDemo"
private const val FPS_LOG_DELTA = 5
private const val RAM_LOG_DELTA_MB = 5L
private const val PSS_REFRESH_MS = 2_000L

/**
 * Оверлей для мониторинга производительности в реальном времени.
 *
 * FPS считается через [withFrameNanos]: подписывается на каждый vsync-сигнал
 * Choreographer и агрегирует кадры за секунду - в отличие от таймера, не создаёт
 * лишних пробуждений и не врёт при пропущенных кадрах.
 *
 * RAM отражает три источника:
 *   - **J** (Java heap) — [Runtime.totalMemory] − [Runtime.freeMemory]. Только managed-объекты.
 *   - **N** (native heap) — [Debug.getNativeHeapAllocatedSize]. С API 26+ сюда входят пиксели
 *     битмапов (NativeAllocationRegistry) — главный источник «невидимой» памяти tiling-кэша.
 *   - **PSS** — [Debug.getPss]. Реальный размер процесса в RSS+swap+graphics, как его видит OS.
 *     Нативный вызов для **текущего** процесса, не идёт через `system_server`. Это критично:
 *     `ActivityManager.getProcessMemoryInfo` с Android 11 rate-limited до 1 вызова / 5 минут
 *     per-process и возвращает кэш — для real-time overlay бесполезен. [Debug.getPss] под капотом
 *     читает /proc/self/statm/smaps и стоит ~1-15 мс, читаем раз в [PSS_REFRESH_MS] на IO.
 *
 * Jank-счётчик получает данные от [JankTracker], который питается из
 * [androidx.metrics.performance.JankStats] в MainActivity.
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
    var memJavaMb by remember { mutableLongStateOf(0L) }
    var memNativeMb by remember { mutableLongStateOf(0L) }
    var memPssMb by remember { mutableLongStateOf(0L) }
    var jankPerSec by remember { mutableIntStateOf(0) }

    // PSS на отдельной IO-корутине: Debug.getPss читает /proc/self/* и стоит ~1-15 мс.
    // Дёргать из withFrameNanos = jank каждые 2 сек, поэтому суспендим в IO.
    LaunchedEffect(Unit) {
        var lastLoggedPss = -1L
        while (true) {
            val pssKb = withContext(Dispatchers.IO) {
                runCatching { Debug.getPss() }.getOrDefault(0L)
            }
            val newPss = pssKb / BYTES_PER_KB // Debug.getPss() отдаёт KB → переводим в MB
            memPssMb = newPss
            if (kotlin.math.abs(newPss - lastLoggedPss) >= RAM_LOG_DELTA_MB) {
                Log.d(TAG, "RAM PSS: ${newPss}MB")
                lastLoggedPss = newPss
            }
            delay(PSS_REFRESH_MS)
        }
    }

    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        var frames = 0
        var lastLoggedFps = -1
        var lastLoggedJ = -1L
        var lastLoggedN = -1L
        while (true) {
            withFrameNanos { now ->
                frames++
                val elapsed = (now - lastTime) / NANOS_PER_SECOND
                if (elapsed >= 1f) {
                    val newFps = (frames / elapsed).toInt()
                    val newJ = Runtime.getRuntime().run {
                        (totalMemory() - freeMemory()) / BYTES_PER_MB
                    }
                    // Native heap включает пиксели Bitmap'ов с API 26+ (NativeAllocationRegistry).
                    // Дешёвый вызов (~μs) — читаем на каждом тике вместе с Java heap.
                    val newN = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB
                    val newJank = JankTracker.flushAndGet()

                    if (kotlin.math.abs(newFps - lastLoggedFps) >= FPS_LOG_DELTA) {
                        Log.d(TAG, "FPS: $newFps")
                        lastLoggedFps = newFps
                    }
                    if (kotlin.math.abs(newJ - lastLoggedJ) >= RAM_LOG_DELTA_MB) {
                        Log.d(TAG, "RAM J: ${newJ}MB")
                        lastLoggedJ = newJ
                    }
                    if (kotlin.math.abs(newN - lastLoggedN) >= RAM_LOG_DELTA_MB) {
                        Log.d(TAG, "RAM N: ${newN}MB")
                        lastLoggedN = newN
                    }
                    if (newJank > 0) Log.w(TAG, "Jank frames/s: $newJank")

                    fps = newFps.toFloat()
                    memJavaMb = newJ
                    memNativeMb = newN
                    jankPerSec = newJank
                    frames = 0
                    lastTime = now
                }
            }
        }
    }

    MetricsOverlayContent(
        imageFullSize = imageSize,
        fps = fps.toInt(),
        ramJavaMb = memJavaMb,
        ramNativeMb = memNativeMb,
        ramPssMb = memPssMb,
        jank = jankPerSec
    )
}

@Composable
private fun MetricsOverlayContent(
    imageFullSize: Long,
    fps: Int,
    ramJavaMb: Long,
    ramNativeMb: Long,
    ramPssMb: Long,
    jank: Int
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
            text = buildString {
                append("FPS: $fps  Jank: $jank/s")
                append('\n')
                append("RAM J:${ramJavaMb} N:${ramNativeMb} PSS:${ramPssMb} MB")
            },
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
            ramJavaMb = 8,
            ramNativeMb = 187,
            ramPssMb = 245,
            jank = 3
        )
    }
}
