package com.shevikina.panzoomdemo.domain.model

/**
 * Смещение содержимого относительно левого верхнего угла viewport, в пикселях экрана.
 */
data class PanOffset(
    val x: Float,
    val y: Float,
) {

    operator fun plus(other: PanOffset) = PanOffset(
        x = x + other.x,
        y = y + other.y,
    )

    companion object {
        val ZERO = PanOffset(0f, 0f)
    }
}
