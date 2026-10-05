package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.repository.IImageSourceRepository
import javax.inject.Inject

class DecodeFullImageUseCase @Inject constructor(
    private val repository: IImageSourceRepository,
) : IDecodeFullImageUseCase {

    override suspend fun invoke(asset: TestImageAsset): DecodedImage = repository.decodeFullImage(asset)
}
