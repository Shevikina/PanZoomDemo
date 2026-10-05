package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

/**
 * Счётчики кадрового цикла, накопленные с прошлого отчёта.
 *
 * Нужны только для hot-лога, поэтому в release весь этот учёт уходит вместе с ним.
 */
internal class FrameLoopStats {

    var wakeups: Int = 0
        private set

    var flushed: Int = 0
        private set

    var recycled: Int = 0
        private set

    var pumped: Int = 0
        private set

    private var lastReportMs = -1L

    /** true, если за отчётный период цикл просыпался вхолостую. */
    val isIdle: Boolean
        get() = flushed == 0 && recycled == 0 && pumped == 0

    /** Добавляет к счётчикам результат одного пробуждения. */
    fun record(
        flushed: Int,
        recycled: Int,
        pumped: Int,
    ) {
        wakeups++
        this.flushed += flushed
        this.recycled += recycled
        this.pumped += pumped
    }

    /**
     * Пора ли писать отчёт.
     *
     * Первый вызов только запоминает отметку времени и отвечает false, потому что мерить
     * ещё не от чего.
     */
    fun isReportDue(frameMs: Long): Boolean {
        if (lastReportMs < 0L) {
            lastReportMs = frameMs
            return false
        }
        return frameMs - lastReportMs >= REPORT_INTERVAL_MS
    }

    /** Обнуляет счётчики и начинает новый отчётный период. */
    fun reset(frameMs: Long) {
        wakeups = 0
        flushed = 0
        recycled = 0
        pumped = 0
        lastReportMs = frameMs
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 1_000L
    }
}
