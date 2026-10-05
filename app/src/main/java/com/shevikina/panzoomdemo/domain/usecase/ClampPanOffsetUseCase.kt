package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.PanBounds
import com.shevikina.panzoomdemo.domain.model.PanOffset
import javax.inject.Inject

class ClampPanOffsetUseCase @Inject constructor() : IClampPanOffsetUseCase {

    override fun invoke(
        offset: PanOffset,
        scale: Float,
        bounds: PanBounds,
    ): PanOffset {
        if (!bounds.isSpecified) return offset

        val scaledWidth = bounds.imageWidth * scale
        val scaledHeight = bounds.imageHeight * scale
        val (minX, maxX) = range(bounds.viewportWidth, scaledWidth)
        val (minY, maxY) = range(bounds.viewportHeight, scaledHeight)

        return PanOffset(
            x = offset.x.coerceIn(minX, maxX),
            y = offset.y.coerceIn(minY, maxY),
        )
    }

    /**
     * Диапазон значений офсета, при которых хотя бы пиксель содержимого виден на экране.
     * При `[viewport] == [contentScaled]` оба конца равны нулю.
     */
    private fun range(
        viewport: Float,
        contentScaled: Float,
    ): Pair<Float, Float> {
        return minOf(0f, viewport - contentScaled) to maxOf(0f, viewport - contentScaled)
    }
}
