package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.repository.IImageSourceRepository
import javax.inject.Inject

class PrepareImageSourceUseCase @Inject constructor(
    private val repository: IImageSourceRepository,
) : IPrepareImageSourceUseCase {

    override suspend fun invoke(asset: TestImageAsset): ImageSource = repository.prepareTiledSource(asset)
}
