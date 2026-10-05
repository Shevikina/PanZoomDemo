package com.shevikina.panzoomdemo.data.datasource

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.data.datasource.BitmapPoolLocalDataSource.Companion.STATS_LOG_INTERVAL_MS

/**
 * Пул битмапов для переиспользования через `BitmapFactory.Options.inBitmap`.
 *
 * Корзины разложены по ключу (width, height), поэтому размер выданного буфера всегда
 * совпадает с запрошенным. Без этого краевые тайлы получали бы растянутую картинку.
 * Вытеснение идёт по LRU через access-order у [LinkedHashMap].
 *
 * @param maxBytes бюджет в байтах, потому что вес буфера зависит от размера тайла.
 */
internal class BitmapPoolLocalDataSource(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) : IBitmapPoolLocalDataSource {

    /**
     * Корзины буферов, от самой старой к самой свежей по времени использования.
     *
     * Создана с access-order, поэтому чтение по ключу переставляет корзину в конец порядка
     * обхода, а кандидат на вытеснение всегда оказывается в начале.
     */
    private val buckets: LinkedHashMap<Long, ArrayDeque<Bitmap>> = LinkedHashMap(0, LOAD_FACTOR, true)
    private var totalCount = 0
    private var totalBytes = 0L

    // Счётчики для строки статистики.
    private var hitCount = 0L
    private var missBucketMissing = 0L
    private var missBucketEmpty = 0L
    private var evictCount = 0L

    /** Время последней записи строки статистики в лог. */
    private var lastLogMs = 0L

    @Synchronized
    override fun acquire(
        width: Int,
        height: Int,
    ): Bitmap? {
        val key = key(width, height)
        val bucket = buckets[key]
        if (bucket == null) {
            missBucketMissing++
            maybeLogStats()
            return null
        }
        while (bucket.isNotEmpty()) {
            val bmp = bucket.removeFirst()
            // Уменьшаем счётчики на любое изъятие, а не только на попадание, чтобы мёртвые
            // буферы не оставались в учёте и не съедали бюджет. Считаем размер по запрошенным
            // ширине и высоте, потому что корзина заведена ровно под них.
            totalCount--
            totalBytes -= bytesOf(width, height)
            // Защита от двойного recycle и страховка на случай потерянной мутации размера.
            if (!bmp.isRecycled && bmp.width == width && bmp.height == height) {
                hitCount++
                maybeLogStats()
                return bmp
            }
        }

        // Корзина существовала, но живых битмапов нужного размера в ней не оказалось.
        missBucketEmpty++
        maybeLogStats()
        return null
    }

    /**
     * Возвращает битмап в корзину своего размера.
     *
     * Если пул переполнен, то [evictOldest] освобождает место, начиная с корзины,
     * к которой дольше всего не обращались.
     * Повторный вызов на уже утилизированном битмапе ничего не делает.
     */
    @Synchronized
    override fun recycle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val bytes = bytesOf(bitmap.width, bitmap.height)
        // Цикл, а не одно вытеснение, потому что место под буфер 512x512 может освободить
        // только десяток мелких.
        while (totalBytes + bytes > maxBytes) {
            if (!evictOldest()) break
        }
        val key = key(bitmap.width, bitmap.height)
        val bucket = buckets.getOrPut(key) { ArrayDeque() }
        bucket.addLast(bitmap)
        totalCount++
        totalBytes += bytes
    }

    /**
     * Заранее аллоцирует [count] буферов размера [width] на [height] и кладёт их в пул.
     *
     * Нужен, чтобы первые тайлы на холодном старте получали готовый буфер и не тратили
     * время на аллокацию. Вызывать до первого [acquire].
     */
    @Synchronized
    override fun prewarm(
        width: Int,
        height: Int,
        count: Int,
    ) {
        repeat(count) {
            recycle(createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }
    }

    @Synchronized
    override fun clear() {
        buckets.values.forEach { bucket ->
            bucket.forEach { if (!it.isRecycled) it.recycle() }
            bucket.clear()
        }
        buckets.clear()
        totalCount = 0
        totalBytes = 0L
    }

    /**
     * Вытесняет один буфер из корзины, к которой дольше всего не обращались.
     *
     * Пустые корзины по дороге удаляются. Дальше поиск идёт по порядку обхода.
     *
     * @return false, если вытеснять больше нечего.
     */
    private fun evictOldest(): Boolean {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val (key, bucket) = iterator.next()
            if (bucket.isNotEmpty()) {
                val bmp = bucket.removeFirst()
                if (!bmp.isRecycled) bmp.recycle()
                totalCount--
                totalBytes -= bytesOfKey(key)
                evictCount++
                // Убираем пустую корзину,
                // чтобы следующий acquire для этого размера дал промах missMissing
                if (bucket.isEmpty()) iterator.remove()
                return true
            }
            iterator.remove()
        }
        return false
    }

    /**
     * Пишет в лог строку статистики пула, но не чаще, чем раз в [STATS_LOG_INTERVAL_MS].
     *
     * Троттлинг живёт в [statsLine]. Пустой результат пропускает запись.
     */
    private fun maybeLogStats() = DemoLog.analytics { statsLine() ?: return }

    /**
     * Собирает строку статистики пула.
     *
     * Формат разбирают скрипты анализа логов, менять его нельзя.
     */
    private fun statsLine(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogMs < STATS_LOG_INTERVAL_MS) return null
        val total = hitCount + missBucketMissing + missBucketEmpty
        if (total == 0L) return null
        lastLogMs = now
        val hitRate = hitCount * PERCENT / total
        val sizesSummary = buckets.entries.joinToString(separator = " ") { (key, bucket) ->
            val w = (key ushr KEY_SHIFT_BITS).toInt()
            val h = (key and LOWER_INT_MASK).toInt()
            "($w×$h:${bucket.size})"
        }
        return "pool stats: hit-rate=$hitRate% items=$totalCount/$maxItemsAtFullTile buckets=${buckets.size} " +
            "missMissing=$missBucketMissing missEmpty=$missBucketEmpty evict=$evictCount sizes=[$sizesSummary] " +
            "bytes=${totalBytes / BYTES_PER_MB}/${maxBytes / BYTES_PER_MB}MB"
    }

    /**
     * Количество буферов полного размера, помещающихся в бюджет.
     *
     * Скрипты ждут в поле items два числа, поэтому лимит приводится к тайлам 512x512.
     * Настоящие байты дописаны в хвост строки и на разбор не влияют.
     */
    private val maxItemsAtFullTile: Int
        get() = (maxBytes / bytesOf(FULL_TILE_SIDE, FULL_TILE_SIDE)).toInt()

    private companion object {
        /**
         * Потолок пула в байтах.
         *
         * Рассчитан на несколько экранов тайлов полного размера с запасом.
         *
         * Занижать нельзя, потому что на крупных тайлах пул вмещает меньше буферов
         * и доля промахов растёт. А каждый промах это новая аллокация.
         */
        const val DEFAULT_MAX_BYTES = 96L * 1024 * 1024
        const val BYTES_PER_MB = 1024 * 1024
        const val BYTES_PER_PIXEL = 4
        const val FULL_TILE_SIDE = 512
        const val STATS_LOG_INTERVAL_MS = 5_000L
        const val LOAD_FACTOR = 0.75f
        const val LOWER_INT_MASK = 0xFFFF_FFFFL
        const val KEY_SHIFT_BITS = 32
        const val PERCENT = 100

        /**
         * Ключ корзины из ширины и высоты.
         *
         * Каждая сторона занимает свои 32 бита (весь диапазон `Int`), поэтому упаковка
         * не теряет данных при любом размере тайла.
         */
        fun key(
            width: Int,
            height: Int,
        ): Long = (width.toLong() shl KEY_SHIFT_BITS) or height.toLong()

        /**
         * Вес буфера по его размерам.
         *
         * В пул попадают только ARGB_8888 (4 байта на пиксель). Прогрев создаёт такие буферы
         * явно, а декод либо переиспользует готовый, либо создаёт новый в том же формате
         * по умолчанию.
         *
         * Считаем по размерам, а не через allocationByteCount, потому что у вытесненного
         * битмапа его уже не спросишь.
         */
        fun bytesOf(
            width: Int,
            height: Int,
        ): Long = width.toLong() * height * BYTES_PER_PIXEL

        /**
         * Вес буфера по ключу его корзины.
         *
         * Нельзя спрашивать размеры у самого битмапа после утилизации,
         * потому что на каждый вызов предупреждение летит в лог.
         *
         * При схлопывании активного слоя через culling лог будет писаться в том же потоке,
         * который собирает кадр, что приведёт к джанку.
         */
        fun bytesOfKey(key: Long): Long = bytesOf(
            width = (key ushr KEY_SHIFT_BITS).toInt(),
            height = (key and LOWER_INT_MASK).toInt(),
        )
    }
}
