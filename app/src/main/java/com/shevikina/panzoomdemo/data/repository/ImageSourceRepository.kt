package com.shevikina.panzoomdemo.data.repository

import com.shevikina.panzoomdemo.data.datasource.IImageAssetLocalDataSource
import com.shevikina.panzoomdemo.data.model.AndroidDecodedImage
import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.repository.IImageSourceRepository
import javax.inject.Inject

internal class ImageSourceRepository @Inject constructor(
    private val dataSource: IImageAssetLocalDataSource,
) : IImageSourceRepository {

    override suspend fun prepareTiledSource(asset: TestImageAsset): ImageSource {
        val prepared = dataSource.prepareSeekableFile(asset.fileName)
        return ImageSource(
            asset = asset,
            path = prepared.file.path,
            sizeBytes = prepared.sizeBytes,
        )
    }

    override suspend fun decodeFullImage(asset: TestImageAsset): DecodedImage =
        AndroidDecodedImage.unmanaged(dataSource.decodeFullBitmap(asset.fileName))
}
