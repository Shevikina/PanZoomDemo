package com.shevikina.panzoomdemo.domain.model

/**
 * Прямоугольник тайла в координатах изображения (целочисленный).
 *
 * Ключ кеша тайлов
 */
data class TileRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top

    /** Пересекается ли тайл с областью [area]. */
    fun intersects(area: ImageRect): Boolean =
        left < area.right && right > area.left && top < area.bottom && bottom > area.top
}
