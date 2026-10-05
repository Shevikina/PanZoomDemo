package com.shevikina.panzoomdemo.data.tiling

import com.shevikina.panzoomdemo.data.datasource.IBitmapPoolLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.IRegionDecoderLocalDataSource
import com.shevikina.panzoomdemo.data.mapper.toAndroid
import com.shevikina.panzoomdemo.data.model.AndroidDecodedImage
import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.DecodedTile
import com.shevikina.panzoomdemo.domain.model.ImageSize
import com.shevikina.panzoomdemo.domain.model.TileGrid
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.domain.tiling.ITileSource

/** Связка декодера и пула буферов над одним открытым изображением. */
internal class AndroidTileSource(
    private val decoder: IRegionDecoderLocalDataSource,
    private val pool: IBitmapPoolLocalDataSource,
    tileSize: Int = TileGrid.DEFAULT_TILE_SIZE,
) : ITileSource {

    override val imageSize: ImageSize = decoder.imageSize

    override val grid: TileGrid = TileGrid(imageSize, tileSize)

    override val overview: DecodedImage? = decoder.overviewBitmap?.let(AndroidDecodedImage::recyclable)

    override suspend fun decodeTile(
        rect: TileRect,
        sampleSize: Int,
    ): DecodedTile? {
        val bitmap = decoder.decodeRegion(rect.toAndroid(), sampleSize) ?: return null
        return DecodedTile(
            rect = rect,
            sampleSize = sampleSize,
            image = AndroidDecodedImage.pooled(bitmap, pool),
        )
    }

    override fun close() {
        decoder.close()
        pool.clear()
    }
}
