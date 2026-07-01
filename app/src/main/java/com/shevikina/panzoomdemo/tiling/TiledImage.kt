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
import java.io.File
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
    private val sourceFile: File,
    private val pool: TilePool,
    val tileSize: Int = 512,
    decoderPoolSize: Int = 3,
) : Closeable {

    // BitmapRegionDecoder, созданный из пути к файлу, под капотом использует mmap —
    // страницы подтягиваются по требованию и освобождаются под давлением, не занимая JVM heap.
    private val decoders: List<BitmapRegionDecoder> = List(decoderPoolSize) { createDecoder() }

    // Канал работает как пул свободных декодеров: receive() — суспендит, если все заняты;
    // send() — возвращает декодер обратно. Capacity = decoderPoolSize, чтобы send никогда не суспендил.
    //
    // onUndeliveredElement обязателен: при `lodScope.cancel()` десятки корутин одновременно
    // отменяются в receive(). Если в этот момент другая корутина делает send(), элемент может
    // «зарендезвузиться» с уже отменённым waiter'ом и потеряться. Без callback'а канал безмолвно
    // пустеет, и весь пул декодеров уходит в /dev/null после первой же смены LOD. Здесь
    // возвращаем декодер обратно через trySend (capacity == decoderPoolSize → всегда влезет).
    private val freeDecoders: Channel<BitmapRegionDecoder> = Channel(
        capacity = decoderPoolSize,
        onUndeliveredElement = ::returnDecoder,
    )

    private fun returnDecoder(decoder: BitmapRegionDecoder) {
        freeDecoders.trySend(decoder)
    }

    // Кешируем до начала декодирования: BitmapRegionDecoder.getWidth() — synchronized-метод,
    // при вызове с main thread во время decodeRegion() на IO-потоке блокирует Choreographer → ANR.
    val fullWidth: Int = decoders.first().width
    val fullHeight: Int = decoders.first().height

    /**
     * Низкоразрешённая версия всей картинки (~1024 px по большей стороне). Рисуется под тайлами
     * как «мутная подложка», покрывая белые дыры при холодном старте и в незагруженных участках
     * viewport. Декодируется один раз здесь же в init.
     * ~2.4 МБ ARGB_8888 при 50k×50k исходнике (sampleSize=64 → 781×781).
     */
    val overviewBitmap: Bitmap? = run {
        var ss = 1
        while (fullWidth / ss > OVERVIEW_MAX_SIDE || fullHeight / ss > OVERVIEW_MAX_SIDE) ss *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = ss }
        BitmapFactory.decodeFile(sourceFile.path, opts).also {
            Log.d(TAG, "overview: ss=$ss size=${it?.width}×${it?.height}px")
        }
    }

    init {
        decoders.forEach { freeDecoders.trySend(it) }
    }

    private fun createDecoder(): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(sourceFile.path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(sourceFile.path, false)
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
     * LOD: выбирает [BitmapFactory.Options.inSampleSize] по текущему масштабу с **гистерезисом**.
     * Пороги переключения вверх (zoom-in, ss уменьшается) и вниз (zoom-out, ss растёт)
     * раздвинуты на ±10 % от центральной точки. Это исключает «дрожание» LOD у самой границы:
     * при scale≈0.5 микро-флуктуации жеста раньше флипали ss=1↔2 каждый кадр.
     *
     * Полосы стабильности по [currentSampleSize]:
     * - 1: scale ≥ 0.45 (зум вниз до 2 при scale < 0.45)
     * - 2: 0.55 > scale ≥ 0.22  (вверх к 1 при > 0.55, вниз к 4 при < 0.22)
     * - 4: 0.28 > scale ≥ 0.11
     * - 8: scale < 0.14
     *
     * Если [currentSampleSize] не задан — поведение совпадает со старой пороговой функцией
     * (выбор по верхним границам).
     */
    fun sampleSizeForScale(scale: Float, currentSampleSize: Int = 1): Int = when (currentSampleSize) {
        1 -> if (scale < THRESHOLD_DOWN_FROM_1) 2 else 1
        2 -> when {
            scale > THRESHOLD_UP_FROM_2 -> 1
            scale < THRESHOLD_DOWN_FROM_2 -> 4
            else -> 2
        }
        4 -> when {
            scale > THRESHOLD_UP_FROM_4 -> 2
            scale < THRESHOLD_DOWN_FROM_4 -> 8
            else -> 4
        }
        else -> if (scale > THRESHOLD_UP_FROM_8) 4 else 8
    }

    /**
     * Декодирует один тайл. [rect] - в координатах оригинального изображения.
     * [sampleSize] — выбранный вызывающим уровень LOD (с учётом гистерезиса/дебаунса).
     * Пытается переиспользовать буфер из [pool] через [BitmapFactory.Options.inBitmap].
     * Возвращает null если декодирование не удалось.
     *
     * Логирует отдельно:
     *   - [waitMs]   — ожидание свободного декодера; растёт при большой очереди тайлов
     *   - [decodeMs] — фактическое время BitmapRegionDecoder.decodeRegion
     */
    suspend fun decodeTile(rect: Rect, sampleSize: Int): Bitmap? {
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
            val expectedW = (rect.width() + sampleSize - 1) / sampleSize
            val expectedH = (rect.height() + sampleSize - 1) / sampleSize
            val poolBitmap = pool.acquire(expectedW, expectedH)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
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
                    "decodeTile: wait=${waitMs}ms decode=${decodeMs}ms [pool-miss] sampleSize=$sampleSize → ${fallback?.width}×${fallback?.height}px [${Thread.currentThread().name}]"
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
                "decodeTile: wait=${waitMs}ms decode=${decodeMs}ms [$poolHit] sampleSize=$sampleSize → ${result?.width}×${result?.height}px [${Thread.currentThread().name}]"
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
        overviewBitmap?.takeIf { !it.isRecycled }?.recycle()
    }

    private companion object {
        // Гистерезис LOD: пороги up/down раздвинуты на ±10 % от центральной точки.
        // up — при zoom-in уменьшаем sampleSize; down — при zoom-out увеличиваем.
        // Центры: 0.5, 0.25, 0.125.
        const val THRESHOLD_DOWN_FROM_1 = 0.45f    // ss=1 → 2
        const val THRESHOLD_UP_FROM_2 = 0.55f      // ss=2 → 1
        const val THRESHOLD_DOWN_FROM_2 = 0.22f    // ss=2 → 4
        const val THRESHOLD_UP_FROM_4 = 0.28f      // ss=4 → 2
        const val THRESHOLD_DOWN_FROM_4 = 0.11f    // ss=4 → 8
        const val THRESHOLD_UP_FROM_8 = 0.14f      // ss=8 → 4

        // Целевая большая сторона overview-битмапа. 1024 px даёт ~2.4 МБ ARGB_8888,
        // визуально достаточно, чтобы читалась общая композиция картинки.
        const val OVERVIEW_MAX_SIDE = 1024
    }
}
