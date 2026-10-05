package com.shevikina.panzoomdemo.presentation.demo.elements.controlpanel

/**
 * Список включённых оптимизаций для свёрнутой панели.
 */
internal fun activeOptionsSummary(
    activeTitles: List<String>,
    noneLabel: String,
): String {
    return if (activeTitles.isEmpty()) {
        noneLabel
    } else {
        activeTitles.joinToString()
    }
}
