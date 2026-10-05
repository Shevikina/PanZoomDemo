package com.shevikina.panzoomdemo.presentation.metrics.color

/**
 * Пороги окраски PSS в мегабайтах.
 *
 * Это суммарный след процесса, к которому прибавляются МБ самого приложения.
 */
internal object PssThresholds {
    const val WARN_MB = 200L
    const val CRIT_MB = 500L
}
