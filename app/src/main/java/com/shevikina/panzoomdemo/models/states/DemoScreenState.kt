package com.shevikina.panzoomdemo.models.states

/**
 * Состояние экрана Демо с тумблерами оптимизаций
 */
data class DemoScreenState(
    val naiveToggle: ToggleState = ToggleState.EMPTY,
    val tilingToggle: ToggleState = ToggleState.EMPTY,
    val cullingToggle: ToggleState = ToggleState.EMPTY,
    val lodToggle: ToggleState = ToggleState.EMPTY,
    val graphicsLayerToggle: ToggleState = ToggleState.EMPTY
)