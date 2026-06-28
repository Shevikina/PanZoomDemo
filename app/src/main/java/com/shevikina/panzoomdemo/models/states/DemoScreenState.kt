package com.shevikina.panzoomdemo.models.states

data class DemoScreenState(
    val naiveToggle: Boolean = false,
    val tilingToggle: Boolean = false,
    val cullingToggle: Boolean = false,
    val lodToggle: Boolean = false,
    val graphicsLayerToggle: Boolean = false
)