package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.TileGrid
import com.shevikina.panzoomdemo.domain.model.TileRect
import javax.inject.Inject

class GetVisibleTilesUseCase @Inject constructor() : IGetVisibleTilesUseCase {

    override fun invoke(
        grid: TileGrid,
        viewport: ImageRect,
        cullingEnabled: Boolean,
        sampleSize: Int,
    ): List<TileRect> {
        return if (cullingEnabled) {
            grid.visibleTiles(viewport, sampleSize)
        } else {
            grid.allTiles(sampleSize)
        }
    }
}
