package com.shevikina.panzoomdemo.domain.usecase

import javax.inject.Inject

/**
 * Набор use cases, которыми пользуется тайловый вьюер.
 * Собран в одну сущность, чтобы конструкторы состояния и его фабрики не разрастались
 * до десятка однотипных параметров.
 */
class TilingUseCases @Inject constructor(
    val calculateViewport: ICalculateViewportUseCase,
    val getVisibleTiles: IGetVisibleTilesUseCase,
    val resolveSampleSize: IResolveSampleSizeUseCase,
    val debounceLod: IDebounceLodUseCase,
    val clampPanOffset: IClampPanOffsetUseCase,
)
