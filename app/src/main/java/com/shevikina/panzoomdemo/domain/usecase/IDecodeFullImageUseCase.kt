package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.TestImageAsset

/** Декодирует изображение целиком: наивный режим демо, намеренно ведущий к OOM. */
interface IDecodeFullImageUseCase {

    suspend operator fun invoke(asset: TestImageAsset): DecodedImage
}
