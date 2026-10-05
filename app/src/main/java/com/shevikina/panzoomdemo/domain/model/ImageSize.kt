package com.shevikina.panzoomdemo.domain.model

/** Размеры изображения в пикселях исходника. */
data class ImageSize(
    val width: Int,
    val height: Int,
) {

    val widthF: Float
        get() = width.toFloat()
    val heightF: Float
        get() = height.toFloat()

    companion object {
        val EMPTY = ImageSize(0, 0)
    }
}
