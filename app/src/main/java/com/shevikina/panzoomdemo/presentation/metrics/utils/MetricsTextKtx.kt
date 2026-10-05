package com.shevikina.panzoomdemo.presentation.metrics.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import com.shevikina.panzoomdemo.presentation.metrics.color.METRIC_LABEL_COLOR

/**
 * Добавляет к строке кусок текста своим цветом.
 *
 * Цвет в оверлее означает статус, поэтому красятся только значения метрик.
 */
fun AnnotatedString.Builder.appendMetric(
    text: String,
    color: Color,
) {
    withStyle(SpanStyle(color = color)) { append(text) }
}

/**
 * Добавляет подпись или разделитель нейтральным цветом.
 *
 * Статуса они не несут, поэтому цветом не выделяются.
 */
fun AnnotatedString.Builder.appendLabel(text: String) {
    appendMetric(text, METRIC_LABEL_COLOR)
}
