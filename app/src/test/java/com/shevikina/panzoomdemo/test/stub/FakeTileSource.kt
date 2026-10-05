package com.shevikina.panzoomdemo.test.stub

import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.DecodedTile
import com.shevikina.panzoomdemo.domain.model.ImageSize
import com.shevikina.panzoomdemo.domain.model.TileGrid
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.domain.tiling.ITileSource

/**
 * Репозиторий тайлов без платформы: декодирование задаётся лямбдой [decode],
 * чтобы тест мог вернуть null, подождать или вклиниться прямо в середину декода.
 */
class FakeTileSource(
    private val decode: suspend (TileRect, Int) -> DecodedTile?,
) : ITileSource {

    override val imageSize: ImageSize = ImageSize(IMAGE_SIDE, IMAGE_SIDE)

    override val grid: TileGrid = TileGrid(imageSize)

    override val overview: DecodedImage? = null

    var closed = false
        private set

    override suspend fun decodeTile(
        rect: TileRect,
        sampleSize: Int,
    ): DecodedTile? = decode(rect, sampleSize)

    override fun close() {
        closed = true
    }

    private companion object {
        const val IMAGE_SIDE = 4096
    }
}
