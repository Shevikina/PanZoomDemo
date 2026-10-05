package com.shevikina.panzoomdemo.domain.tiling

import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.DecodedTile
import com.shevikina.panzoomdemo.domain.model.ImageSize
import com.shevikina.panzoomdemo.domain.model.TileGrid
import com.shevikina.panzoomdemo.domain.model.TileRect

/**
 * Открытое изображение, из которого можно читать тайлы: сессия регионального декодирования.
 *
 * Намеренно не репозиторий.
 * Тут экземпляр владеет нативными ресурсами (декодеры, пул буферов)
 * и живёт столько же, сколько открытое изображение, а также обязан быть закрыт.
 */
interface ITileSource {

    val imageSize: ImageSize

    /** Сетка тайлов поверх открытого изображения. */
    val grid: TileGrid

    /** Низкоразрешённая подложка всего изображения; рисуется под тайлами. */
    val overview: DecodedImage?

    /** Декодирует один тайл. Возвращает null, если декодер не справился. */
    suspend fun decodeTile(
        rect: TileRect,
        sampleSize: Int,
    ): DecodedTile?

    /** Освобождает декодеры и пул буферов. */
    fun close()
}
