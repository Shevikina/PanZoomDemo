package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.TileGrid
import com.shevikina.panzoomdemo.domain.model.TileRect

/**
 * Отбирает тайлы под текущий viewport.
 * При выключенном culling возвращает всю сетку.
 *
 * Уровень детализации приходит параметром, потому что от него зависит размер клетки.
 */
interface IGetVisibleTilesUseCase {

    operator fun invoke(
        grid: TileGrid,
        viewport: ImageRect,
        cullingEnabled: Boolean,
        sampleSize: Int,
    ): List<TileRect>
}
