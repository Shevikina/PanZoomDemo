package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset

/** Готовит изображение к региональному декодированию. */
interface IPrepareImageSourceUseCase {

    suspend operator fun invoke(asset: TestImageAsset): ImageSource
}
