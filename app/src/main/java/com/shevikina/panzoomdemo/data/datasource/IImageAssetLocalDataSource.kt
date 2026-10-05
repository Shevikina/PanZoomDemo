package com.shevikina.panzoomdemo.data.datasource

import android.graphics.Bitmap
import com.shevikina.panzoomdemo.data.model.PreparedAsset

/** Доступ к тестовым изображениям, лежащим в assets. */
internal interface IImageAssetLocalDataSource {

    /**
     * Копирует ассет в cacheDir и возвращает файл вместе с его размером.
     *
     * `BitmapRegionDecoder` требует источник с произвольным доступом, а поток из ассетов
     * читается только подряд.
     */
    suspend fun prepareSeekableFile(assetName: String): PreparedAsset

    /** Размер ассета в байтах; -1, если прочитать не удалось. */
    suspend fun readAssetSizeBytes(assetName: String): Long

    /** Декодирует ассет целиком - наивный режим демо. */
    suspend fun decodeFullBitmap(assetName: String): Bitmap
}
