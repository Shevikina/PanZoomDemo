package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.model.DecodedTile
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.domain.tiling.ITileSource
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TileLoadingController.Companion.MAX_INFLIGHT_JOBS
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TileLoadingController.Companion.MAX_LAUNCHES_PER_TICK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Очередь и запуск задач декодирования тайлов.
 *
 * Дренируется порциями.
 *
 * @param onTileDecoded зовётся с io, когда тайл готов.
 * Результат забирает кадровый цикл на main, и без сигнала ему пришлось бы просыпаться каждый кадр
 * и проверять, не появилось ли чего.
 * @param onDecodeFailed зовётся с io, когда буфер под тайл выделить не удалось.
 * Владелец обязан перенести обработку на main,
 * потому что чистить кеш и показывать ошибку отсюда нельзя.
 */
internal class TileLoadingController(
    private val tileSource: ITileSource,
    private val dispatchers: ICoroutineDispatchers,
    private val onTileDecoded: () -> Unit = {},
    private val onDecodeFailed: (Throwable) -> Unit = {},
) {

    /**
     * Тайлы, ждущие запуска корутины декода.
     * */
    private val toLoadQueue = ArrayDeque<TileRect>()

    /**
     * Зеркало [toLoadQueue] для ускорения проверки стоит ли уже тайл в очереди или нет.
     * Оба контейнера меняются только вместе.
     *
     * Создан, для оптимизации поиска элементов:
     * - [toLoadQueue] поиск по [ArrayDeque]: O(n)
     * - [toLoadQueued] поиск по [HashSet]: O(1)
     * */
    private val toLoadQueued = HashSet<TileRect>()

    private val loadingJobs = ConcurrentHashMap<TileRect, Job>()

    /**
     * Декодированные на io тайлы, ещё не внесённые в кеш.
     *
     * Пишется с io, читается с main.
     * */
    private val pending = ConcurrentHashMap<TileRect, DecodedImage>()

    /** Скоуп текущей LOD-генерации
     *
     * Определен, чтобы одним cancel() отменяет все in-flight задачи
     * */
    private var lodScope: CoroutineScope? = null

    /**
     * Уровень детализации, под который запускаются задачи.
     *
     * Читается с io-потока внутри воркера, поэтому @Volatile.
     */
    @Volatile
    var activeSampleSize: Int = 1

    val queuedCount: Int
        get() = toLoadQueue.size

    val loadingCount: Int
        get() = loadingJobs.size

    val pendingCount: Int
        get() = pending.size

    fun enqueue(rects: Collection<TileRect>) {
        toLoadQueue.addAll(rects)
        toLoadQueued.addAll(rects)
    }

    fun isTracked(rect: TileRect): Boolean =
        rect in toLoadQueued || loadingJobs.containsKey(rect) || pending.containsKey(rect)

    /**
     * Снимает тайл с загрузки, когда он ушёл из viewport.
     *
     * В display list такой тайл не попадал, так что буфер возвращаем сразу.
     * */
    fun cancelTile(rect: TileRect) {
        loadingJobs.remove(rect)?.cancel()
        pending.remove(rect)?.release()
    }

    /**
     * Убирает всё, что относится к тайлам вне [needed].
     * Отменяет запущенные корутины, отпускает уже декодированные тайлы и вычищает очередь на запуск.
     *
     * @return true, если хоть в одной из трёх категорий нашлось что убирать.
     */
    fun cullOutOfViewport(needed: Set<TileRect>): Boolean {
        val staleJobs = loadingJobs.keys - needed
        staleJobs.forEach { key -> loadingJobs.remove(key)?.cancel() }

        val stalePending = pending.keys - needed
        stalePending.forEach { key -> pending.remove(key)?.release() }

        val staleQueued = toLoadQueued - needed
        if (staleQueued.isNotEmpty()) {
            toLoadQueued.removeAll(staleQueued)
            toLoadQueue.removeAll(staleQueued)
        }

        if (staleJobs.isNotEmpty() || stalePending.isNotEmpty()) {
            DemoLog.hot { "cullOutOfViewport: cancel=${staleJobs.size} stalePending=${stalePending.size}" }
        }
        return staleJobs.isNotEmpty() || stalePending.isNotEmpty() || staleQueued.isNotEmpty()
    }

    /**
     * Гасит всю текущую генерацию задач и чистит очередь.
     *
     * Зовётся при смене LOD, потому что и запущенные задачи, и уже готовые тайлы
     * декодируются под старый sampleSize.
     *
     * @return количество готовых тайлов, которые пришлось выбросить.
     */
    fun cancelGeneration(): Int {
        lodScope?.cancel()
        lodScope = null
        loadingJobs.clear()
        val stale = pending.size
        pending.values.forEach(DecodedImage::release)
        pending.clear()
        toLoadQueue.clear()
        toLoadQueued.clear()
        return stale
    }

    /**
     * Запускает декод для не более чем [MAX_LAUNCHES_PER_TICK] тайлов за вызов.
     *
     * С очереди может сняться больше, потому что уже ненужные тайлы выбрасываются
     * и в счёт запусков не идут.
     *
     * Если число задач в работе упёрлось в [MAX_INFLIGHT_JOBS], то цикл остановится раньше.
     *
     * @return количество реально запущенных задач.
     */
    fun launchUpToLimit(
        parentScope: CoroutineScope,
        needed: Set<TileRect>,
    ): Int {
        var launched = 0
        val scope = obtainLodScope(parentScope)
        val sampleSize = activeSampleSize
        // Потолок in-flight стоит в условии цикла, чтобы loadingJobs не распухал.
        while (
            launched < MAX_LAUNCHES_PER_TICK &&
            toLoadQueue.isNotEmpty() &&
            loadingJobs.size < MAX_INFLIGHT_JOBS
        ) {
            val rect = toLoadQueue.removeFirst()
            toLoadQueued.remove(rect)
            if (shouldLaunch(rect, needed)) {
                // Задача создаётся остановленной и стартует уже после записи в мапу,
                // чтобы её finally не снял запись, которой ещё нет.
                val job = launchDecode(scope, rect, sampleSize)
                loadingJobs[rect] = job
                job.start()
                launched++
            }
        }
        return launched
    }

    /**
     * Проверяет, стоит ли запускать декод тайла
     *
     * Отдельно смотрит [pending], потому что уже декодированный тайл ещё не лежит в кеше,
     * и без этой проверки он декодировался бы второй раз.
     */
    private fun shouldLaunch(
        rect: TileRect,
        needed: Set<TileRect>,
    ): Boolean {
        return rect in needed &&
            !loadingJobs.containsKey(rect) &&
            !pending.containsKey(rect)
    }

    /**
     * Создаёт остановленную задачу декода одного тайла.
     *
     * Перед декодом проверяет, что LOD не сменился, пока задача ждала в очереди.
     *
     * Запись из [loadingJobs] снимается в finally и только там, чтобы завершённая задача
     * не осталась в мапе и тайл можно было запросить снова.
     */
    private fun launchDecode(
        scope: CoroutineScope,
        rect: TileRect,
        sampleSize: Int,
    ): Job = scope.launch(
        context = dispatchers.io,
        start = CoroutineStart.LAZY,
    ) {
        try {
            ensureActive()
            if (activeSampleSize != sampleSize) return@launch

            val tile = decodeOrNull(rect, sampleSize) ?: return@launch
            publishDecoded(rect, tile)
        } finally {
            // Снимаем только свою запись,
            // потому что для этого rect уже могла стартовать следующая генерация
            loadingJobs.remove(rect, coroutineContext[Job])
        }
    }

    /**
     * Декодирует тайл и возвращает null, если декодер не справился или кончилась память.
     *
     * **Ловит [OutOfMemoryError], чтобы не уронить процесс.**
     * На API 26+ пиксели лежат в нативной куче, и до ловимого OOM дело обычно не доходит,
     * потому что процесс раньше заберёт LMKD.
     * В проекте minSdk=24, а на API 24 и 25 буфер тайла считается в java-куче под heapgrowthlimit,
     * и там это настоящий ловимый OutOfMemoryError.
     *
     * @return декодированный тайл или null, если запускать дальше нечего.
     */
    private suspend fun decodeOrNull(
        rect: TileRect,
        sampleSize: Int,
    ): DecodedTile? = try {
        tileSource.decodeTile(rect, sampleSize)
    } catch (e: OutOfMemoryError) {
        onDecodeFailed(e)
        null
    }

    /**
     * Кладёт готовый тайл в [pending] и будит кадровый цикл.
     *
     * Если задачу отменят между декодом и записью, то буфер отпускается явно, чтобы он не потерялся.
     */
    private suspend fun publishDecoded(
        rect: TileRect,
        tile: DecodedTile,
    ) {
        try {
            currentCoroutineContext().ensureActive()
            pending[rect] = tile.image
            onTileDecoded()
        } catch (e: CancellationException) {
            tile.image.release()
            throw e
        }
    }

    /**
     * Переносит готовые тайлы из [pending] в [cache].
     *
     * Вызывается кадровым циклом с main-потока, потому что [cache] с других потоков
     * трогать нельзя.
     *
     * @return количество перенесённых тайлов.
     */
    fun flushPendingInto(cache: TileCache): Int {
        if (pending.isEmpty()) return 0
        var flushed = 0
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (rect, image) = iterator.next()
            iterator.remove()
            // DrawableTile заводится один раз на тайл,
            // чтобы обёртка ImageBitmap не пересоздавалась на каждый кадр.
            cache.putActive(rect, DrawableTile(image))
            flushed++
        }
        return flushed
    }

    fun dispose() {
        toLoadQueue.clear()
        toLoadQueued.clear()
        lodScope?.cancel()
        lodScope = null
        loadingJobs.clear()
        pending.values.forEach(DecodedImage::release)
        pending.clear()
    }

    /**
     * Возвращает скоуп текущей LOD-генерации.
     * Если скоупа нет или он уже отменён, то создаётся новый.
     *
     * Скоуп строится на SupervisorJob с родителем из [parent], чтобы отмена [parent] гасила
     * генерацию целиком, а падение одной задачи декода не уносило с собой остальные.
     */
    private fun obtainLodScope(parent: CoroutineScope): CoroutineScope {
        val existing = lodScope
        if (existing != null && existing.isActive) return existing
        val fresh = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        lodScope = fresh
        return fresh
    }

    private companion object {
        /**
         * Потолок на число задач, запускаемых за один вызов.
         *
         * Без потолка тысяча задач заняла бы сотни миллисекунд
         * и привело бы к нескольким выпавшим кадрам.
         * */
        const val MAX_LAUNCHES_PER_TICK = 64

        /**
         * Потолок на число задач декода, одновременно находящихся в работе.
         *
         * Cтраховка от разрастания loadingJobs.
         * Число взято на основе 32 одновременных задач на 3 декодерах с запасом.
         * */
        const val MAX_INFLIGHT_JOBS = 256
    }
}
