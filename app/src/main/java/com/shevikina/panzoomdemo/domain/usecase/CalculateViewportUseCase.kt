package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.PanOffset
import javax.inject.Inject

class CalculateViewportUseCase @Inject constructor() : ICalculateViewportUseCase {

    override fun invoke(
        screenWidth: Float,
        screenHeight: Float,
        offset: PanOffset,
        scale: Float,
    ): ImageRect {
        if (scale <= 0f) return ImageRect.EMPTY

        return ImageRect(
            left = (-offset.x / scale).coerceAtLeast(0f),
            top = (-offset.y / scale).coerceAtLeast(0f),
            right = (screenWidth - offset.x) / scale,
            bottom = (screenHeight - offset.y) / scale,
        )
    }
}
