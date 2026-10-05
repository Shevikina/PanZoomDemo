package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.usecase.IOpenTiledImageUseCase
import com.shevikina.panzoomdemo.domain.usecase.TilingUseCases
import javax.inject.Inject

/**
 * Собирает [TiledImageViewerState] под конкретный источник.
 *
 * Создавать состояние напрямую нельзя, потому что вместе с ним открываются декодеры.
 * Закрыть их обязан владелец через [TiledImageViewerState.dispose].
 */
class TiledImageViewerStateFactory @Inject constructor(
    private val openTiledImage: IOpenTiledImageUseCase,
    private val useCases: TilingUseCases,
    private val dispatchers: ICoroutineDispatchers,
) {

    suspend fun create(source: ImageSource): TiledImageViewerState = TiledImageViewerState(
        tileSource = openTiledImage(source),
        useCases = useCases,
        dispatchers = dispatchers,
    )
}
