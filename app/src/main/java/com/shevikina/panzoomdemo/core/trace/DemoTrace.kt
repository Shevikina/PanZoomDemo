package com.shevikina.panzoomdemo.core.trace

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Секции трейса, переживающие ожидание корутины.
 *
 * Обычная [Trace.beginSection] принадлежит потоку и обязана закрыться на нём же. Корутина
 * после suspend просыпается на другом потоке, поэтому закрытие ушло бы не в свою секцию,
 * а своя осталась бы открытой до конца трейса. Для кода без suspend хватает [Trace] напрямую.
 */
internal object DemoTrace {

    /**
     * Счётчик cookie для асинхронных секций.
     *
     * Один на процесс, потому что cookie отличает пересекающиеся секции с одним именем.
     * Счётчик на экземпляр выдавал бы одинаковые номера из разных источников, и секции
     * слиплись бы в одну.
     */
    private val cookies = AtomicInteger(0)

    /**
     * Выполняет [block] внутри асинхронной секции трейса [name].
     *
     * Асинхронные секции связаны по cookie, а не по потоку, поэтому переживают suspend
     * внутри [block].
     * До API 29 их нет, и трейс просто не пишется.
     */
    suspend fun <T> asyncSection(
        name: String,
        block: suspend () -> T,
    ): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return block()
        val cookie = cookies.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        try {
            return block()
        } finally {
            Trace.endAsyncSection(name, cookie)
        }
    }
}
