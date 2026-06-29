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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
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
 * [BitmapRegionDecoder] не потокобезопасен, поэтому держим пул из [decoderPoolSize]
 * независимых декодеров. Раздаём их через [Channel]: суспенд-«семафор», который
 * возвращает декодер обратно по `send` после использования. Это позволяет N задачам
 * декодировать параллельно вместо одной за раз, как было с глобальным `Mutex`.
 */
class TiledImage(
    inputStream: InputStream,
    private val pool: TilePool,
    val tileSize: Int = 512,
    decoderPoolSize: Int = 3,
) : Closeable {

    // BitmapRegionDecoder.newInstance(InputStream) потребляет поток целиком при создании,
    // поэтому второй экземпляр из того же InputStream не сделать. Читаем байты один раз
    // и создаём декодеры из ByteArray.
    private val sourceBytes: ByteArray = inputStream.use { it.readBytes() }

    private val decoders: List<BitmapRegionDecoder> = List(decoderPoolSize) { createDecoder() }

    // Канал работает как пул свободных декодеров: receive() — суспендит, если все заняты;
    // send() — возвращает декодер обратно. Capacity = decoderPoolSize, чтобы send никогда не суспендил.
    private val freeDecoders: Channel<BitmapRegionDecoder> = Channel(decoderPoolSize)

    // Кешируем до начала декодирования: BitmapRegionDecoder.getWidth() — synchronized-метод,
    // при вызове с main thread во время decodeRegion() на IO-потоке блокирует Choreographer → ANR.
    val fullWidth: Int = decoders.first().width
    val fullHeight: Int = decoders.first().height

    init {
        decoders.forEach { freeDecoders.trySend(it) }
    }

    private fun createDecoder(): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(sourceBytes, 0, sourceBytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(sourceBytes, 0, sourceBytes.size, false)
        } ?: error("Failed to create BitmapRegionDecoder")

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
     *   - [waitMs]   — ожидание свободного декодера; растёт при большой очереди тайлов
     *   - [decodeMs] — фактическое время BitmapRegionDecoder.decodeRegion
     */
    suspend fun decodeTile(rect: Rect, scale: Float): Bitmap? {
        // Если задача уже отменена (тайл ушёл из viewport), не занимаем декодер и не тратим decodeRegion.
        coroutineContext.ensureActive()
        val waitStart = SystemClock.elapsedRealtime()
        Trace.beginSection("decodeTile.wait")
        val decoder = freeDecoders.receive()
        Trace.endSection()
        try {
            // Повторная проверка: пока ждали свободный декодер, задачу могли отменить.
            coroutineContext.ensureActive()
            val decodeStart = SystemClock.elapsedRealtime()
            val waitMs = decodeStart - waitStart

            // Прогнозируем размер ВЫХОДНОГО битмапа: ceil(side / sampleSize). По нему
            // запрашиваем точно подходящий буфер из пула — иначе для edge-тайлов мы получали
            // 512×512 буфер на 304×512 декод, result === poolBitmap, но валидные пиксели только
            // в 304×512 области → drawImage рисовал «растянутый» тайл.
            val ss = sampleSizeForScale(scale)
            val expectedW = (rect.width() + ss - 1) / ss
            val expectedH = (rect.height() + ss - 1) / ss
            val poolBitmap = pool.acquire(expectedW, expectedH)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ss
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
                    "decodeTile: wait=${waitMs}ms decode=${decodeMs}ms [pool-miss] sampleSize=$ss → ${fallback?.width}×${fallback?.height}px [${Thread.currentThread().name}]"
                )
                return fallback
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
                "decodeTile: wait=${waitMs}ms decode=${decodeMs}ms [$poolHit] sampleSize=$ss → ${result?.width}×${result?.height}px [${Thread.currentThread().name}]"
            )
            return result
        } finally {
            // Декодер всегда обязан вернуться в пул, иначе пул со временем «протечёт» досуха.
            freeDecoders.send(decoder)
        }
    }

    override fun close() {
        freeDecoders.close()
        decoders.forEach { it.recycle() }
    }
}
