package com.shevikina.panzoomdemo.data.datasource

import android.graphics.Bitmap

/**
 * Пул битмапов для переиспользования буферов при декодировании.
 *
 * Экземпляр живёт столько же, сколько открытое изображение.
 */
internal interface IBitmapPoolLocalDataSource {

    /** Буфер точно запрошенного размера или null, если подходящего нет. */
    fun acquire(
        width: Int,
        height: Int,
    ): Bitmap?

    /** Возвращает буфер в пул. Повторный вызов на утилизированном битмапе игнорируется. */
    fun recycle(bitmap: Bitmap)

    /** Аллоцирует буферы заранее, чтобы первые декоды не платили за аллокацию. */
    fun prewarm(
        width: Int,
        height: Int,
        count: Int,
    )

    /** Утилизирует все буферы пула. */
    fun clear()
}
