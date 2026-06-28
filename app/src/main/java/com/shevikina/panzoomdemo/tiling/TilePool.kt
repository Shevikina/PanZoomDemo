package com.shevikina.panzoomdemo.tiling

import android.graphics.Bitmap

/**
 * Пул битмапов для переиспользования через [BitmapFactory.Options.inBitmap].
 * Снижает давление на GC при частой загрузке тайлов - не плодим аллокации, а переиспользуем буферы.
 */
class TilePool(private val maxPoolSize: Int = 20) {

    private val free = ArrayDeque<Bitmap>()

    @Synchronized
    fun acquire(): Bitmap? = free.removeFirstOrNull()?.takeIf { !it.isRecycled }

    /**
     * Возвращает битмап в пул. Если пул полон - утилизирует.
     */
    @Synchronized
    fun recycle(bitmap: Bitmap) {
        if (free.size < maxPoolSize) {
            free.addLast(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun clear() {
        free.forEach { it.recycle() }
        free.clear()
    }
}
