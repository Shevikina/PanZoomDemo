package com.shevikina.panzoomdemo.domain.repository

import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset

/** Доступ к тестовым изображениям приложения. */
interface IImageSourceRepository {

    /**
     * Подготовить seekable-источник для регионального декодирования.
     * Загрузить картинку обычным файлом, чтобы декодер смог по ней прыгать к тем строкам/блокам которые нужны
     * */
    suspend fun prepareTiledSource(asset: TestImageAsset): ImageSource

    /** Декодировать изображение целиком (наивный режим) */
    suspend fun decodeFullImage(asset: TestImageAsset): DecodedImage
}
