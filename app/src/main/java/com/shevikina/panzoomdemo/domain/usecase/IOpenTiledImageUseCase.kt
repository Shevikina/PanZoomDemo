package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.tiling.ITileSource

/**
 * Открывает изображение для тайлового чтения: заводит декодеры и прогревает пул буферов.
 *
 * Возвращает открытую сессию, а не данные, поэтому вызывающий обязан закрыть её через [ITileSource.close].
 */
interface IOpenTiledImageUseCase {

    suspend operator fun invoke(source: ImageSource): ITileSource
}
