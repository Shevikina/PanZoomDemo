package com.shevikina.panzoomdemo.metrics

import com.shevikina.panzoomdemo.metrics.JankTracker.flushAndGet
import com.shevikina.panzoomdemo.metrics.JankTracker.recordJank
import java.util.concurrent.atomic.AtomicInteger

/**
 * Мост между [androidx.metrics.performance.JankStats] (фоновый поток) и MetricsOverlay (main).
 *
 * JankStats вызывает [recordJank] на каждый джанк-кадр.
 * MetricsOverlay вызывает [flushAndGet] раз в секунду — читает накопленное и сбрасывает счётчик.
 */
object JankTracker {

    private val pendingJanks = AtomicInteger(0)

    fun recordJank() {
        pendingJanks.incrementAndGet()
    }

    fun flushAndGet(): Int = pendingJanks.getAndSet(0)
}
