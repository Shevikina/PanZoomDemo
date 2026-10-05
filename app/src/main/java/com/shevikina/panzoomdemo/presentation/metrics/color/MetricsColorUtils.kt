package com.shevikina.panzoomdemo.presentation.metrics.color

import androidx.compose.ui.graphics.Color

/** Подписи и разделители статуса не несут, поэтому нейтральные. */
internal val METRIC_LABEL_COLOR = Color.LightGray

internal fun fpsColor(fps: Int): Color = when {
    fps >= FpsThresholds.GOOD -> Color.Green
    fps >= FpsThresholds.ACCEPTABLE -> Color.Yellow
    else -> Color.Red
}

internal fun jankColor(jank: Int): Color = when {
    jank == 0 -> Color.Green
    jank <= JANK_ACCEPTABLE -> Color.Yellow
    else -> Color.Red
}

/**
 * Красит значение памяти по двум порогам.
 *
 * Зелёный означает благоприятное значение, жёлтый пройдёт на среднем устройстве и не пройдёт
 * на слабом, красный критическое.
 */
internal fun ramColor(
    mb: Long,
    warnMb: Long,
    critMb: Long,
): Color = when {
    mb < warnMb -> Color.Green
    mb < critMb -> Color.Yellow
    else -> Color.Red
}

internal fun javaHeapColor(mb: Long): Color = ramColor(
    mb = mb,
    warnMb = JavaHeapThresholds.WARN_MB,
    critMb = JavaHeapThresholds.CRIT_MB,
)

internal fun nativeHeapColor(mb: Long): Color = ramColor(
    mb = mb,
    warnMb = NativeHeapThresholds.WARN_MB,
    critMb = NativeHeapThresholds.CRIT_MB,
)

internal fun pssColor(mb: Long): Color = ramColor(
    mb = mb,
    warnMb = PssThresholds.WARN_MB,
    critMb = PssThresholds.CRIT_MB,
)

/**
 * Зелёный только когда держим всё, что нужно.
 *
 * Иначе часть картинки на экране не прогружена, и зелёная цифра врала бы ровно об этом.
 */
internal fun tilesColor(
    held: Int,
    needed: Int,
): Color = if (held >= needed) Color.Green else Color.Yellow
