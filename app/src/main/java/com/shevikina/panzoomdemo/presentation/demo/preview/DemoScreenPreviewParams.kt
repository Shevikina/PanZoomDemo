package com.shevikina.panzoomdemo.presentation.demo.preview

import com.shevikina.panzoomdemo.presentation.demo.model.DemoScreenUi

/** Параметры одного превью главного экрана. */
data class DemoScreenPreviewParams(
    val ui: DemoScreenUi,
    val sheetExpanded: Boolean = false,
)
