package com.shevikina.panzoomdemo.tiling

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log

private const val TAG = "PanZoomDemo"

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
 * Корзины живут в access-order [LinkedHashMap]: при acquire/recycle бакет уезжает в хвост карты,
 * чтобы при переполнении пула вытеснять LRU — давно неиспользуемые размеры. Это даёт
 * pool hit-rate ~60-80% против ~20% у предыдущей схемы с фиксированным `perBucketLimit=8`:
 * на demo-сцене 12+ уникальных размеров (4 LOD × 3+ edge-варианта), и битмапы постоянно
 * вытеснялись из переполненных корзин текущего LOD при добавлении edge-вариантов соседа.
 */
class TilePool(private val maxTotalCount: Int = DEFAULT_MAX_TOTAL_COUNT) {

    // access-order=true → buckets[key] (get) перемещает элемент в хвост. Голова = LRU.
    private val buckets: LinkedHashMap<Long, ArrayDeque<Bitmap>> = LinkedHashMap(0, LOAD_FACTOR, true)
    private var totalCount = 0

    // Диагностика: счётчики hit/miss + причина промаха + периодический Log.d.
    private var hitCount = 0L
    private var missBucketMissing = 0L
    private var missBucketEmpty = 0L
    private var evictCount = 0L
    private var lastLogMs = 0L

    @Synchronized
    fun acquire(width: Int, height: Int): Bitmap? {
        val key = key(width, height)
        // get() при accessOrder=true перенесёт корзину в хвост (mark as recently used).
        val bucket = buckets[key]
        if (bucket == null) {
            missBucketMissing++
            maybeLogStats()
            return null
        }
        while (bucket.isNotEmpty()) {
            val bmp = bucket.removeFirst()
            // Защита от двойного recycle + страховка от потерянной мутации размера.
            if (!bmp.isRecycled && bmp.width == width && bmp.height == height) {
                totalCount--
                hitCount++
                maybeLogStats()
                return bmp
            }
        }
        // Корзина существовала, но в ней не оказалось живых битмапов нужного размера.
        missBucketEmpty++
        maybeLogStats()
        return null
    }

    /**
     * Возвращает битмап в соответствующую размеру корзину. Если пул переполнен —
     * вытесняет один LRU-битмап из самой давно неиспользуемой корзины (head LinkedHashMap).
     * Дублирующий вызов на уже утилизированном битмапе игнорируется.
     */
    @Synchronized
    fun recycle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (totalCount >= maxTotalCount) {
            evictOldest()
        }
        val key = key(bitmap.width, bitmap.height)
        val bucket = buckets.getOrPut(key) { ArrayDeque() }
        bucket.addLast(bitmap)
        totalCount++
    }

    @Synchronized
    fun clear() {
        buckets.values.forEach { bucket ->
            bucket.forEach { if (!it.isRecycled) it.recycle() }
            bucket.clear()
        }
        buckets.clear()
        totalCount = 0
    }

    /**
     * Вытесняет первый битмап из самой давно неиспользуемой корзины (head iteration order).
     * Если в head-корзине нет живых битмапов — она удаляется, ищем дальше.
     */
    private fun evictOldest() {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val (_, bucket) = iterator.next()
            if (bucket.isNotEmpty()) {
                val bmp = bucket.removeFirst()
                if (!bmp.isRecycled) bmp.recycle()
                totalCount--
                evictCount++
                // Пустую корзину после изъятия последнего битмапа — убираем, чтобы acquire
                // следующего acquire для этого размера давал миссу "BucketMissing", а не
                // "BucketEmpty" (точнее отражает причину промаха).
                if (bucket.isEmpty()) iterator.remove()
                return
            }
            // Корзина уже пустая (например, после прошлой эвикции) — снимаем мусор.
            iterator.remove()
        }
    }

    /**
     * Логирует pool stats раз в [STATS_LOG_INTERVAL_MS] миллисекунд. Не хочется писать
     * в каждом acquire/recycle: тысячи строк в logcat в секунду перегружают и
     * скрывают остальные сообщения.
     */
    private fun maybeLogStats() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogMs < STATS_LOG_INTERVAL_MS) return
        lastLogMs = now
        val total = hitCount + missBucketMissing + missBucketEmpty
        if (total == 0L) return
        val hitRate = hitCount * 100 / total
        val sizesSummary = buckets.entries.joinToString(separator = " ") { (key, bucket) ->
            val w = (key ushr 32).toInt()
            val h = (key and LOWER_INT_MASK).toInt()
            "(${w}×${h}:${bucket.size})"
        }
        Log.d(
            TAG,
            "pool stats: hit-rate=${hitRate}% items=$totalCount/$maxTotalCount buckets=${buckets.size} " +
                "missMissing=$missBucketMissing missEmpty=$missBucketEmpty evict=$evictCount sizes=[$sizesSummary]"
        )
    }

    private companion object {
        // 96 вместо прежнего 64 (BUCKETS=8 × perBucketLimit=8). +32 битмапа = +30 МБ native при
        // самом крупном tile 512×512 ARGB_8888, но pool hit-rate растёт в 3-4 раза — компромисс ок.
        const val DEFAULT_MAX_TOTAL_COUNT = 96
        const val STATS_LOG_INTERVAL_MS = 5_000L
        const val LOAD_FACTOR = 0.75f
        const val LOWER_INT_MASK = 0xFFFF_FFFFL

        // Размеры тайлов до 16384px → влезут в 14 бит. С запасом берём 32 бита под каждый.
        fun key(width: Int, height: Int): Long = (width.toLong() shl 32) or height.toLong()
    }
}
