package com.shevikina.panzoomdemo.models.states

import com.shevikina.panzoomdemo.models.enums.Enemies
import com.shevikina.panzoomdemo.models.enums.Weapons

/**
 * UI-состояние переключателя
 */
data class ToggleState(
    val enable: Boolean,
    val title: String,
    val subtitle: String? = null,
    val onChange: (Boolean) -> Unit,
    val enemy: Enemies,
    val weapon: Weapons
)
