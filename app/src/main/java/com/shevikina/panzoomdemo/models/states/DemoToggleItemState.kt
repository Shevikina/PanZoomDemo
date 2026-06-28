package com.shevikina.panzoomdemo.models.states

import com.shevikina.panzoomdemo.models.enums.Enemies
import com.shevikina.panzoomdemo.models.enums.Weapons

/**
 * UI-состояние поля с переключателем
 */
data class DemoToggleItemState(
    val title: String,
    val subtitle: String? = null,
    val enabled: Boolean,
    val checked: Boolean,
    val onChange: (Boolean) -> Unit,
    val enemy: Enemies,
    val weapon: Weapons
)
