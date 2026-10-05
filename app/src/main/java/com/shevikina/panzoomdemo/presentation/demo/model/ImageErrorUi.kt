package com.shevikina.panzoomdemo.presentation.demo.model

/**
 * UI-модель снека ошибки.
 *
 * Тип выделяется жирным, текст исключения идёт следом как есть.
 */
data class ImageErrorUi(
    val type: String,
    val message: String?,
)
