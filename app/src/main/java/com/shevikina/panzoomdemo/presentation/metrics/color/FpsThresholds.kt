package com.shevikina.panzoomdemo.presentation.metrics.color

/**
 * Пороги окраски частоты кадров.
 *
 * [GOOD] это комфортная частота, ниже начинается заметная потеря плавности,
 * а ниже [ACCEPTABLE] рывки видно глазом.
 */
internal object FpsThresholds {
    const val GOOD = 50
    const val ACCEPTABLE = 30
}
