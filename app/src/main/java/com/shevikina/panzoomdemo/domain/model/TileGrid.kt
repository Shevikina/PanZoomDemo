package com.shevikina.panzoomdemo.domain.model

/**
 * Сетка тайлов, наложенная на изображение [imageSize].
 *
 * Клетка растёт вместе с уровнем детализации и равна `tileSize * sampleSize` пикселей
 * исходника. Поэтому на любом уровне тайл декодируется в одни и те же [tileSize] выходных
 * пикселей, а число видимых тайлов почти не зависит от зума. Это классическая пирамида.
 *
 * Краевые тайлы обрезаются по границам изображения, поэтому бывают меньше клетки.
 */
class TileGrid(
    val imageSize: ImageSize,
    val tileSize: Int = DEFAULT_TILE_SIZE,
) {

    /** Сторона клетки в пикселях исходника на уровне [sampleSize]. */
    fun cellSize(sampleSize: Int): Int = tileSize * sampleSize

    /**
     * Тайлы уровня [sampleSize], пересекающие [viewport]. Основа viewport culling.
     *
     * @param sampleSize делитель разрешения, от 1 и дальше степенями двойки.
     */
    fun visibleTiles(
        viewport: ImageRect,
        sampleSize: Int,
    ): List<TileRect> = buildList {
        if (imageSize.width <= 0 || imageSize.height <= 0) return@buildList
        if (viewport.right <= 0f || viewport.bottom <= 0f) return@buildList

        val cell = cellSize(sampleSize)
        val x0 = (viewport.left / cell).toInt().coerceAtLeast(0)
        val y0 = (viewport.top / cell).toInt().coerceAtLeast(0)
        val x1 = (viewport.right / cell).toInt()
        val y1 = (viewport.bottom / cell).toInt()

        for (ty in y0..y1) {
            for (tx in x0..x1) {
                val left = tx * cell
                val top = ty * cell
                val right = minOf(left + cell, imageSize.width)
                val bottom = minOf(top + cell, imageSize.height)

                if (right > left && bottom > top) {
                    add(TileRect(left, top, right, bottom))
                }
            }
        }
    }

    /** Все тайлы уровня [sampleSize]: режим с выключенным culling. */
    fun allTiles(sampleSize: Int): List<TileRect> = visibleTiles(ImageRect.full(imageSize), sampleSize)

    companion object {
        /**
         * Сторона тайла в выходных пикселях.
         *
         * Компромисс между числом вызовов decodeRegion и объёмом лишних пикселей на краю.
         * В пикселях исходника клетка больше во столько раз, каков текущий `inSampleSize`.
         */
        const val DEFAULT_TILE_SIZE = 512
    }
}
