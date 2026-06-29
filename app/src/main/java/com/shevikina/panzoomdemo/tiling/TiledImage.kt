package com.shevikina.panzoomdemo.tiling

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.InputStream
import kotlin.coroutines.coroutineContext

private const val TAG = "PanZoomDemo"

/**
 * Обёртка над [BitmapRegionDecoder] с поддержкой culling и LOD.
 *
 * Декодирует только видимые тайлы фиксированного размера [tileSize]×[tileSize] px
 * в координатах оригинального изображения. Переиспользует буферы из [pool].
 *
 * [BitmapRegionDecoder] не потокобезопасен - все вызовы [decodeTile] сериализуются через [Mutex].
 */
class TiledImage(
    private val inputStream: InputStream,
    private val pool: TilePool,
    val tileSize: Int = 512,
) : Closeable {

    private val decoder: BitmapRegionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(inputStream)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(inputStream, false)
    } ?: error("Failed to create BitmapRegionDecoder")

    private val lock = Mutex()

    // Кешируем до начала декодирования: BitmapRegionDecoder.getWidth() — synchronized-метод,
    // при вызове с main thread во время decodeRegion() на IO-потоке блокирует Choreographer → ANR.
    val fullWidth: Int = decoder.width
    val fullHeight: Int = decoder.height

    /**
     * Возвращает [Rect]-ы тайлов в координатах изображения, пересекающих [viewportInImage].
     */
    fun visibleTileRects(viewportInImage: RectF): List<Rect> = buildList {
        val x0 = (viewportInImage.left / tileSize).toInt().coerceAtLeast(0)
        val y0 = (viewportInImage.top / tileSize).toInt().coerceAtLeast(0)
        val x1 = (viewportInImage.right / tileSize).toInt()
        val y1 = (viewportInImage.bottom / tileSize).toInt()

        for (ty in y0..y1) {
            for (tx in x0..x1) {
                val left = tx * tileSize
                val top = ty * tileSize
                val right = minOf(left + tileSize, fullWidth)
                val bottom = minOf(top + tileSize, fullHeight)
                if (right > left && bottom > top) add(Rect(left, top, right, bottom))
            }
        }
    }

    /**
     * LOD: выбирает [BitmapFactory.Options.inSampleSize] по текущему масштабу.
     * Меньший масштаб → крупнее шаг субдискретизации → меньше памяти.
     */
    fun sampleSizeForScale(scale: Float): Int = when {
        scale >= 0.5f -> 1
        scale >= 0.25f -> 2
        scale >= 0.125f -> 4
        else -> 8
    }

    /**
     * Декодирует один тайл. [rect] - в координатах оригинального изображения.
     * Пытается переиспользовать буфер из [pool] через [BitmapFactory.Options.inBitmap].
     * Возвращает null если декодирование не удалось.
     *
     * Логирует отдельно:
     *   - [waitMs]   — ожидание [lock] (Mutex); растёт при большой очереди тайлов
     *   - [decodeMs] — фактическое время BitmapRegionDecoder.decodeRegion
     */
    suspend fun decodeTile(rect: Rect, scale: Float): Bitmap? {
        // Если задача уже отменена (тайл ушёл из viewport), не занимаем Mutex и не тратим decodeRegion.
        coroutineContext.ensureActive()
        val waitStart = SystemClock.elapsedRealtime()
        Trace.beginSection("decodeTile.wait")
        return lock.withLock {
            Trace.endSection()
            // Повторная проверка: пока ждали Mutex (могли простоять секунды), задачу могли отменить.
            coroutineContext.ensureActive()
            val decodeStart = SystemClock.elapsedRealtime()
            val waitMs = decodeStart - waitStart

            val poolBitmap = pool.acquire()
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeForScale(scale)
                inMutable = true
                inBitmap = poolBitmap
            }

            val result: Bitmap?
            val usedPoolBitmap: Boolean

            Trace.beginSection("decodeTile.decode")
            try {
                result = decoder.decodeRegion(rect, opts)
                usedPoolBitmap = result === poolBitmap
            } catch (e: IllegalArgumentException) {
                // inBitmap не подошёл по размеру — возвращаем в пул и декодируем заново без него
                poolBitmap?.let { pool.recycle(it) }
                val fallback = decoder.decodeRegion(rect, opts.apply { inBitmap = null })
                Trace.endSection()
                val decodeMs = SystemClock.elapsedRealtime() - decodeStart
                Log.d(
                    TAG,
                    "decodeTile: wait=${waitMs}ms decode=${decodeMs}ms [pool-miss] sampleSize=${opts.inSampleSize} → ${fallback?.width}×${fallback?.height}px [${Thread.currentThread().name}]"
                )
                return@withLock fallback
            }
            Trace.endSection()

            // decodeRegion выделил новый буфер вместо poolBitmap — возвращаем poolBitmap в пул
            if (!usedPoolBitmap && poolBitmap != null) {
                pool.recycle(poolBitmap)
            }

            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            val poolHit = if (usedPoolBitmap) "pool-hit" else "pool-miss"
            Log.d(
                TAG,
                "decodeTile: wait=${waitMs}ms decode=${decodeMs}ms [$poolHit] sampleSize=${opts.inSampleSize} → ${result?.width}×${result?.height}px [${Thread.currentThread().name}]"
            )
            result
        }
    }

    override fun close() {
        decoder.recycle()
        inputStream.close()
    }
}
