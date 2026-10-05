package com.shevikina.panzoomdemo.presentation.demo.elements.error

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.shevikina.panzoomdemo.presentation.demo.model.ImageErrorUi

/**
 * «Ошибка загрузки изображения **ТипИсключения**: текст».
 *
 * [prefix] приходит уже разрешённым из ресурсов, поэтому функция остаётся чистой для тестов.
 */
internal fun imageErrorText(
    prefix: String,
    error: ImageErrorUi?,
): AnnotatedString = buildAnnotatedString {
    append(prefix)
    if (error == null) return@buildAnnotatedString

    append(" ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(error.type) }
    error.message?.takeIf { it.isNotBlank() }?.let { message ->
        append(": ")
        append(message)
    }
}
