package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.SampleSize
import javax.inject.Inject

class ResolveSampleSizeUseCase @Inject constructor() : IResolveSampleSizeUseCase {

    override fun invoke(
        scale: Float,
        currentSampleSize: Int,
        lodEnabled: Boolean,
    ): Int {
        if (!lodEnabled) return SampleSize.MIN

        return when (currentSampleSize) {
            SampleSize.MIN -> {
                if (scale < SampleSize.THRESHOLD_DOWN_FROM_1) {
                    SampleSize.HALF
                } else {
                    SampleSize.MIN
                }
            }

            SampleSize.HALF -> when {
                scale > SampleSize.THRESHOLD_UP_FROM_2 -> SampleSize.MIN
                scale < SampleSize.THRESHOLD_DOWN_FROM_2 -> SampleSize.QUARTER
                else -> SampleSize.HALF
            }

            SampleSize.QUARTER -> when {
                scale > SampleSize.THRESHOLD_UP_FROM_4 -> SampleSize.HALF
                scale < SampleSize.THRESHOLD_DOWN_FROM_4 -> SampleSize.EIGHTH
                else -> SampleSize.QUARTER
            }

            else -> {
                if (scale > SampleSize.THRESHOLD_UP_FROM_8) {
                    SampleSize.QUARTER
                } else {
                    SampleSize.EIGHTH
                }
            }
        }
    }
}
