package com.shevikina.panzoomdemo.presentation.demo.model

import androidx.annotation.StringRes
import com.shevikina.panzoomdemo.domain.model.DemoToggle

/**
 * UI-модель строки с переключателем
 */
data class DemoToggleItemUi(
    val toggle: DemoToggle,
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val enabled: Boolean,
    val checked: Boolean,
)
