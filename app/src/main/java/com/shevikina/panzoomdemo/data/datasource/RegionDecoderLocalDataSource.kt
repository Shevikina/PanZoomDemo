package com.shevikina.panzoomdemo.data.datasource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.core.trace.DemoTrace
import com.shevikina.panzoomdemo.domain.model.ImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Декодирует произвольную область файла и переиспользует буферы из [pool].
 *
 * [BitmapRegionDecoder] не потокобезопасен, поэтому держим пул из [decoderPoolSize] декодеров
 * и раздаём их через [Channel] как suspend-семафор.
 */
internal class RegionDecoderLocalDataSource(
    private val sourceFile: File,
    private val pool: IBitmapPoolLocalDataSource,
    decoderPoolSize: Int = DECODER_POOL_SIZE,
) : IRegionDecoderLocalDataSource {

    /**
     * Декодеры, созданные из пути к файлу.
     */
    private val decoders: List<BitmapRegionDecoder> = List(decoderPoolSize) { createDecoder() }

    /**
     * Пул свободных декодеров, работающий как suspend-семафор.
     *
     * Чтение суспендит, когда все заняты, а отправка возвращает декодер обратно.
     * [onUndeliveredElement] нужен, чтобы декодер не потерялся при встрече с уже отменённым
     * получателем после первой же смены LOD.
     */
    private val freeDecoders: Channel<BitmapRegionDecoder> = Channel(
        capacity = decoderPoolSize,
        onUndeliveredElement = ::returnDecoder,
    )

    /**
     * Количество свободных декодеров.
     */
    private val freeCount = AtomicInteger(0)

    private val poolSize: Int = decoderPoolSize

    /**
     * Возвращает декодер в канал.
     */
    private fun returnDecoder(decoder: BitmapRegionDecoder) {
        if (freeDecoders.trySend(decoder).isSuccess) {
            freeCount.incrementAndGet()
        } else {
            decoder.recycle()
        }
    }

    /**
     * Ширина исходного изображения в пикселях.
     */
    private val fullWidth: Int = decoders.first().width

    /** Высота исходного изображения в пикселях. */
    private val fullHeight: Int = decoders.first().height

    override val imageSize: ImageSize = ImageSize(fullWidth, fullHeight)

    /**
     * Уменьшенная копия всего изображения.
     *
     * Закрывает белые дыры на холодном старте и в ещё не загруженных участках viewport.
     * Декодируется один раз при создании источника, потому что дальше её только читают.
     * Null, если декодер не справился с файлом.
     */
    override val overviewBitmap: Bitmap? = run {
        var ss = 1
        while (fullWidth / ss > OVERVIEW_MAX_SIDE || fullHeight / ss > OVERVIEW_MAX_SIDE) ss *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = ss }
        BitmapFactory.decodeFile(sourceFile.path, opts).also {
            DemoLog.cold { "overview: ss=$ss size=${it?.width}×${it?.height}px" }
        }
    }

    init {
        decoders.forEach(::returnDecoder)
    }

    private fun createDecoder(): BitmapRegionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(sourceFile.path)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(sourceFile.path, false)
    } ?: error("Failed to create BitmapRegionDecoder")

    /**
     * Декодирует область [rect] с заданным [sampleSize] через пул декодеров.
     *
     * Буфер берётся из [pool]. В лог уходят время ожидания свободного декодера и время
     * самого декода.
     */
    override suspend fun decodeRegion(
        rect: Rect,
        sampleSize: Int,
    ): Bitmap? {
        // Если задача уже отменена, потому что тайл ушёл из viewport, то декодер не занимаем.
        currentCoroutineContext().ensureActive()
        val waitStart = SystemClock.elapsedRealtime()
        val decoder = DemoTrace.asyncSection(TRACE_WAIT) {
            // Пока ждём декодер, изображение могли сменить и канал закрыть. Отмена скоупа
            // и закрытие канала гонятся между собой, и ждущий иногда ловит закрытие первым.
            // Трактуем это как отмену, а не как падение процесса.
            try {
                freeDecoders.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw CancellationException("TiledImage закрыт во время ожидания декодера", e)
            }
        }
        freeCount.decrementAndGet()
        try {
            // Проверяем ещё раз, потому что пока ждали свободный декодер, задачу могли отменить.
            currentCoroutineContext().ensureActive()
            val decodeStart = SystemClock.elapsedRealtime()
            val waitMs = decodeStart - waitStart

            // Считаем размер выходного битмапа и просим из пула буфер ровно под него.
            // Иначе краевой тайл получил бы буфер полного размера, заполнил бы только часть
            // его пикселей, и отрисовка растянула бы картинку.
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

            // Декод не суспендит, поэтому обычной секции хватает.
            Trace.beginSection(TRACE_DECODE)
            try {
                result = decoder.decodeRegion(rect, opts)
                usedPoolBitmap = result === poolBitmap
            } catch (_: IllegalArgumentException) {
                // inBitmap не подошёл по размеру, поэтому возвращаем буфер в пул и декодируем без него.
                poolBitmap?.let { pool.recycle(it) }
                val fallback = decoder.decodeRegion(rect, opts.apply { inBitmap = null })
                logDecode(waitMs, SystemClock.elapsedRealtime() - decodeStart, POOL_MISS, sampleSize, fallback)
                return fallback
            } finally {
                Trace.endSection()
            }

            // Декодер выделил свой буфер вместо нашего, поэтому наш возвращаем в пул.
            if (!usedPoolBitmap && poolBitmap != null) {
                pool.recycle(poolBitmap)
            }

            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            logDecode(waitMs, decodeMs, if (usedPoolBitmap) POOL_HIT else POOL_MISS, sampleSize, result)
            return result
        } finally {
            // Декодер обязан вернуться в пул всегда, чтобы пул со временем не опустел.
            // Канал мог закрыться, пока мы декодировали, и тогда [returnDecoder] утилизирует
            // декодер сам.
            returnDecoder(decoder)
        }
    }

    /**
     * Пишет в лог результат одного декода.
     *
     * Формат разбирают скрипты анализа логов, менять его нельзя. Новые поля дописываются
     * только в хвост строки, тогда разбор не ломается.
     */
    private fun logDecode(
        waitMs: Long,
        decodeMs: Long,
        poolHit: String,
        sampleSize: Int,
        bitmap: Bitmap?,
    ) {
        DemoLog.analytics {
            "decodeTile: wait=${waitMs}ms decode=${decodeMs}ms [$poolHit] " +
                "sampleSize=$sampleSize → ${bitmap?.width}×${bitmap?.height}px " +
                "[${Thread.currentThread().name}] free=${freeCount.get()}/$poolSize"
        }
    }

    override fun close() {
        freeDecoders.close()
        // Утилизируем только простаивающие декодеры (уже лежат в канале).
        // decodeRegion - это блокирующий JNI-вызов, отмена корутины его не прерывает,
        // и утилизация занятого декодера уронила бы процесс прямо посреди декода на другом потоке.
        // Занятые утилизируются сами, когда вернутся в уже закрытый канал.
        while (true) {
            val decoder = freeDecoders.tryReceive().getOrNull() ?: break
            freeCount.decrementAndGet()
            decoder.recycle()
        }
        // overviewBitmap не утилизируем, потому что Compose может держать обёртку над ним
        // до следующей рекомпозиции. Отпускаем ссылку, остальное сделает сборщик мусора.
    }

    private companion object {
        /** Количество декодеров в пуле. */
        const val DECODER_POOL_SIZE = 3

        const val POOL_HIT = "pool-hit"
        const val POOL_MISS = "pool-miss"

        const val TRACE_WAIT = "decodeTile.wait"
        const val TRACE_DECODE = "decodeTile.decode"

        /**
         * Целевая длина большей стороны overview-подложки в пикселях.
         *
         * Этого хватает, чтобы читалась общая композиция картинки, а сама подложка
         * оставалась дешёвой по памяти независимо от размера исходника.
         */
        const val OVERVIEW_MAX_SIDE = 1024
    }
}
