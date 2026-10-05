package com.shevikina.panzoomdemo.data.model

import android.graphics.Bitmap
import com.shevikina.panzoomdemo.data.datasource.IBitmapPoolLocalDataSource
import com.shevikina.panzoomdemo.domain.model.DecodedImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Реализация [DecodedImage] поверх [Bitmap].
 *
 * Тайлы возвращаются в пул, а не утилизируются, поэтому [release] отдаёт буфер владельцу.
 * Повторный [release] игнорируется, чтобы один буфер не попал в пул дважды.
 */
internal class AndroidDecodedImage private constructor(
    val bitmap: Bitmap,
    private val onRelease: (Bitmap) -> Unit,
) : DecodedImage {

    /**
     * Признак того, что буфер уже отдан владельцу.
     *
     * Atomic, а не обычный флаг, потому что отменённая задача декодирования отдаёт буфер
     * с io, а кеш с main, и попасть сюда дважды они могут одновременно.
     */
    private val released = AtomicBoolean(false)

    override val width: Int
        get() = bitmap.width

    override val height: Int
        get() = bitmap.height

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        onRelease(bitmap)
    }

    companion object {

        /** Тайл, буфер которого принадлежит пулу. */
        fun pooled(
            bitmap: Bitmap,
            pool: IBitmapPoolLocalDataSource,
        ): AndroidDecodedImage = AndroidDecodedImage(bitmap) { pool.recycle(it) }

        /**
         * Битмап без пула для наивного режима.
         *
         * Освобождение отдано сборщику мусора, потому что HWUI может держать ссылку
         * в display list до конца текущего кадра, и явная утилизация даёт артефакты.
         */
        fun unmanaged(bitmap: Bitmap): AndroidDecodedImage = AndroidDecodedImage(bitmap) { }

        /**
         * Битмап, который владелец утилизирует сам, то есть overview-подложка.
         *
         * Звать [release] можно только тогда, когда картинку уже некому рисовать, и лучше
         * не в том же кадре, потому что HWUI держит ссылку в display list.
         */
        fun recyclable(bitmap: Bitmap): AndroidDecodedImage = AndroidDecodedImage(bitmap) { it.recycle() }
    }
}
