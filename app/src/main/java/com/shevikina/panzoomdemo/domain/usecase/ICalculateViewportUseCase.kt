package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.PanOffset

/** Переводит экранный viewport в прямоугольник в координатах изображения. */
interface ICalculateViewportUseCase {

    operator fun invoke(
        screenWidth: Float,
        screenHeight: Float,
        offset: PanOffset,
        scale: Float,
    ): ImageRect
}
