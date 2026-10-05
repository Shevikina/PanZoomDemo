package com.shevikina.panzoomdemo.presentation.metrics.color

/**
 * Пороги окраски java-кучи в мегабайтах.
 *
 * Тайлы живут в native, поэтому здесь всегда единицы мегабайт.
 * Зелёная строка J это само по себе утверждение, что с кучей ничего не происходит
 * и смотреть надо на native.
 */
internal object JavaHeapThresholds {
    const val WARN_MB = 64L
    const val CRIT_MB = 128L
}
