package com.shevikina.panzoomdemo.presentation.metrics

/** Уровень детализации оверлея с метриками. */
enum class MetricsOverlayVisibility {
    FULL,
    COMPACT,
    HIDDEN,
    ;

    /**
     * Возвращает следующий уровень по кругу.
     */
    fun next() = when (this) {
        FULL -> COMPACT
        COMPACT -> HIDDEN
        HIDDEN -> FULL
    }
}
