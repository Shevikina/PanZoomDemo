package com.shevikina.panzoomdemo.domain.usecase

/**
 * Выбирает `inSampleSize` по текущему масштабу с гистерезисом.
 * При выключенном LOD всегда возвращает полное разрешение.
 */
interface IResolveSampleSizeUseCase {

    operator fun invoke(
        scale: Float,
        currentSampleSize: Int,
        lodEnabled: Boolean,
    ): Int
}
