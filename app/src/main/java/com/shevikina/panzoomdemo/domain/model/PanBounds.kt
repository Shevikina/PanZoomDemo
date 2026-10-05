package com.shevikina.panzoomdemo.domain.model

/**
 * Границы для ограничения pan-офсета: размеры viewport и несмасштабированного изображения.
 */
data class PanBounds(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val imageWidth: Float,
    val imageHeight: Float,
) {
    val isSpecified: Boolean
        get() = viewportWidth > 0f && viewportHeight > 0f && imageWidth > 0f && imageHeight > 0f

    companion object {

        /**
         * Границы ещё не измерены. Офсет не ограничивается».
         */
        val UNSPECIFIED = PanBounds(0f, 0f, 0f, 0f)
    }
}
