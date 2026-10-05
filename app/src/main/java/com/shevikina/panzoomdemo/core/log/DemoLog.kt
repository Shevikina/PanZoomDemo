package com.shevikina.panzoomdemo.core.log

import android.util.Log
import com.shevikina.panzoomdemo.BuildConfig

/**
 * Логи демо с разделением по назначению.
 *
 * - HOT: диагностика внутри кадра (updateTiles, flush, счётчики рекомпозиций). Есть только в debug сборке.
 * - ANALYTICS: строки, которые разбирает scripts/logcat_to_csv.py. Формат сообщения менять нельзя!
 * - COLD: события уровня «открыли изображение», «сменился LOD»; остаются всегда.
 *
 * Сам вызов `Log.d` R8 не удаляет, потому что `-assumenosideeffects` на `android.util.Log`
 * съел бы аналитику вместе с горячими строками.
 */
internal object DemoLog {

    /** Тег для фильтрации логов приложения. Один на всё приложение, по нему же фильтруют скрипты. */
    const val TAG = "PanZoomDemo"

    /**
     * Диагностика внутри кадра.
     *
     * На release R8 выбрасывает и проверку, и склейку строки в лямбде.
     * На debug проверка остаётся, но за выключенный лог всё равно не платим.
     */
    inline fun hot(message: () -> String) {
        if (BuildConfig.HOT_LOGS) Log.d(TAG, message())
    }

    /** Аналитические логи для снятия метрик. Формат строк разбирают скрипты, менять его нельзя. */
    inline fun analytics(message: () -> String) {
        Log.d(TAG, message())
    }

    /** Редкие события уровня действий пользователя. */
    inline fun cold(message: () -> String) {
        Log.d(TAG, message())
    }

    fun warn(message: String) = Log.w(TAG, message)

    fun error(message: String) = Log.e(TAG, message)
}
