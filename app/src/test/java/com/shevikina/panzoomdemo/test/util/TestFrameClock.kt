package com.shevikina.panzoomdemo.test.util

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.delay

/**
 * Часы кадров для тестов анимаций: `animateDecay` требует [MonotonicFrameClock] в контексте
 * корутины и без него падает. Тянуть ради этого ui-test не нужно.
 *
 * Кадр отдаётся через [delay], то есть по виртуальному времени тестового планировщика:
 * без ожидания анимация проскакивала бы целиком за один `runCurrent`, и проверить
 * её остановку было бы нечем.
 */
class TestFrameClock : MonotonicFrameClock {

    private var frameNanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        delay(FRAME_MILLIS)
        frameNanos += FRAME_MILLIS * NANOS_PER_MILLI
        return onFrame(frameNanos)
    }

    companion object {
        const val FRAME_MILLIS = 16L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
