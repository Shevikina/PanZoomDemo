package com.shevikina.panzoomdemo.models.states

/**
 * Cостояние переключателя
 */
data class ToggleState(
    val enabled: Boolean = true,
    val checked: Boolean = false
) {
    companion object {
        val EMPTY = ToggleState()
    }
}