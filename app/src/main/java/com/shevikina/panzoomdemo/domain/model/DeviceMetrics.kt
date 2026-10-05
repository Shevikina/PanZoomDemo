package com.shevikina.panzoomdemo.domain.model

/**
 * Снимок метрик процесса за секунду.
 *
 * FPS сюда не входит: он снимается с vsync-колбэка Compose и по своей природе принадлежит
 * presentation-слою.
 */
data class DeviceMetrics(
    val javaHeapMb: Long,
    val nativeHeapMb: Long,
    val pssMb: Long,
    val jankPerSecond: Int,
) {
    companion object {
        val EMPTY = DeviceMetrics(0L, 0L, 0L, 0)
    }
}
