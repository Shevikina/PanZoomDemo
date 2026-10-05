package com.shevikina.panzoomdemo.data.datasource

import android.graphics.Bitmap
import android.graphics.Rect
import com.shevikina.panzoomdemo.domain.model.ImageSize

/** Региональное декодирование одного файла изображения. */
internal interface IRegionDecoderLocalDataSource {

    val imageSize: ImageSize

    /** Низкоразрешённая версия всей картинки, которая рисуется подложкой под тайлами. */
    val overviewBitmap: Bitmap?

    /** Декодирует область [rect] с прореживанием [sampleSize]. */
    suspend fun decodeRegion(
        rect: Rect,
        sampleSize: Int,
    ): Bitmap?

    /** Освобождает декодеры. */
    fun close()
}
