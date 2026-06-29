package com.shevikina.panzoomdemo.tiling

import android.graphics.Bitmap

/**
 * Пул битмапов для переиспользования через [BitmapFactory.Options.inBitmap].
 * Снижает давление на GC при частой загрузке тайлов - не плодим аллокации, а переиспользуем буферы.
 *
 * Битмапы разложены по корзинам по ключу `(width, height)` нативного буфера. Этим гарантируется,
 * что `inBitmap` для декода будет ровно того размера, что ожидает `decodeRegion`:
 * - для интерьерных тайлов это `tileSize/sampleSize × tileSize/sampleSize` (например, 512×512 при ss=1);
 * - для краевых (last-column/last-row) это меньшие, уникальные размеры (например, 304×512).
 *
 * Без размерной разбивки `decodeRegion(opts.inBitmap=512×512)` для краевого 304×512-региона возвращает
 * `result === inBitmap` с буфером 512×512, в котором валидны только 304×512 — это и есть «растянутый
 * тайл» при отрисовке через `drawImage(dstSize=rect.width()*scale, ...)`.
 *
 * Лимит [maxPoolSize] делится поровну между ожидаемыми корзинами (8 размеров: 4 LOD × ≤2 краевых
 * варианта на LOD). Корзина переполнена → старый buffer recycle-ится сразу.
 */
class TilePool(maxPoolSize: Int = 64) {

    // На практике корзин 6-12 (4 интерьерных размера + 4-8 краевых). 8 — компромисс.
    private val perBucketLimit: Int = (maxPoolSize / BUCKETS).coerceAtLeast(2)

    private val freeBySize = HashMap<Long, ArrayDeque<Bitmap>>()

    @Synchronized
    fun acquire(width: Int, height: Int): Bitmap? {
        val bucket = freeBySize[key(width, height)] ?: return null
        while (bucket.isNotEmpty()) {
            val bmp = bucket.removeFirst()
            // Защита от двойного recycle + страховка от потерянной мутации размера.
            if (!bmp.isRecycled && bmp.width == width && bmp.height == height) return bmp
        }
        return null
    }

    /**
     * Возвращает битмап в соответствующую размеру корзину. Если корзина полна — `recycle()`.
     * Дублирующий вызов на уже утилизированном битмапе игнорируется.
     */
    @Synchronized
    fun recycle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val bucket = freeBySize.getOrPut(key(bitmap.width, bitmap.height)) { ArrayDeque() }
        if (bucket.size < perBucketLimit) {
            bucket.addLast(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun clear() {
        freeBySize.values.forEach { bucket ->
            bucket.forEach { if (!it.isRecycled) it.recycle() }
            bucket.clear()
        }
        freeBySize.clear()
    }

    private companion object {
        const val BUCKETS = 8

        // Размеры тайлов до 16384px → влезут в 14 бит. С запасом берём 32 бита под каждый.
        fun key(width: Int, height: Int): Long = (width.toLong() shl 32) or height.toLong()
    }
}
