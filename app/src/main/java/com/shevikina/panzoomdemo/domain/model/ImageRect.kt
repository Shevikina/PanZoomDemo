package com.shevikina.panzoomdemo.domain.model

/** Прямоугольник в координатах изображения (дробный): видимая область, viewport. */
data class ImageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        val EMPTY = ImageRect(0f, 0f, 0f, 0f)

        /** Прямоугольник, покрывающий изображение целиком. */
        fun full(size: ImageSize) = ImageRect(0f, 0f, size.widthF, size.heightF)
    }
}
