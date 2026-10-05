package com.shevikina.panzoomdemo.presentation.metrics.color

/**
 * Пороги окраски native-кучи в мегабайтах.
 *
 * Сюда уходят битмапы тайлов (главная цифра демо).
 * Хорошая конфигурация зелёная, а провалы - красные.
 */
internal object NativeHeapThresholds {
    const val WARN_MB = 250L
    const val CRIT_MB = 500L
}
