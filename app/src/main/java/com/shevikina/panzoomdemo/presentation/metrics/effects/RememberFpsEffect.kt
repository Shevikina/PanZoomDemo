package com.shevikina.panzoomdemo.presentation.metrics.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.shevikina.panzoomdemo.core.log.DemoLog
import kotlin.math.abs

private const val NANOS_PER_SECOND = 1_000_000_000f

/** На сколько кадров должно измениться значение, чтобы уехать в аналитический лог. */
private const val FPS_LOG_DELTA = 5

/**
 * Считает частоту кадров и обновляет её раз в секунду.
 *
 * [withFrameNanos] подписывается на vsync-сигнал Choreographer, поэтому счётчик не создаёт
 * собственных пробуждений и не добавляет работы в кадр, который сам же измеряет.
 *
 * В лог значение уходит не каждую секунду, а только при заметном изменении, чтобы строки
 * не забивали вывод на ровной картинке.
 *
 * @return последнее посчитанное значение в кадрах в секунду.
 */
@Composable
internal fun rememberFpsEffect(): Int {
    var fps by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        var frames = 0
        var lastLoggedFps = -1
        while (true) {
            withFrameNanos { now ->
                frames++
                val elapsed = (now - lastTime) / NANOS_PER_SECOND
                if (elapsed >= 1f) {
                    val newFps = (frames / elapsed).toInt()
                    if (abs(newFps - lastLoggedFps) >= FPS_LOG_DELTA) {
                        DemoLog.analytics { "FPS: $newFps" }
                        lastLoggedFps = newFps
                    }
                    fps = newFps
                    frames = 0
                    lastTime = now
                }
            }
        }
    }
    return fps
}
