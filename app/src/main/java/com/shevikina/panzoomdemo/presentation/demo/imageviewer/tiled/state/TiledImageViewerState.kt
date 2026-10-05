package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import androidx.compose.runtime.IntState
import androidx.compose.runtime.mutableIntStateOf
import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.ImageRect
import com.shevikina.panzoomdemo.domain.model.PanOffset
import com.shevikina.panzoomdemo.domain.model.TileRect
import com.shevikina.panzoomdemo.domain.tiling.ITileSource
import com.shevikina.panzoomdemo.domain.usecase.TilingUseCases
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture.PanZoomState
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TiledImageViewerState.Companion.OVERVIEW_RELEASE_DELAY_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

/**
 * Оркестратор тайлового вьюера.
 * Сводит жест, уровень детализации, кеш и загрузку.
 *
 * Все мутации строго с main-потока.
 */
class TiledImageViewerState internal constructor(
    private val tileSource: ITileSource,
    private val useCases: TilingUseCases,
    dispatchers: ICoroutineDispatchers,
) {

    val panZoom = PanZoomState(useCases.clampPanOffset)

    private val cache = TileCache()
    private val lod = LodController(useCases.resolveSampleSize, useCases.debounceLod)

    /**
     * Канал, переносящий ошибку декода с io на main.
     *
     * Нехватка памяти обнаруживается в [TileLoadingController] на io, а чистить кеш
     * и показывать снек можно только с main.
     *
     * CONFLATED, потому что при нехватке памяти в канал падает сразу пачка задач,
     * а показать нужно одно сообщение.
     */
    private val decodeFailures = Channel<Throwable>(Channel.CONFLATED)

    private val loading = TileLoadingController(
        tileSource = tileSource,
        dispatchers = dispatchers,
        onTileDecoded = ::signalFrameWork,
        onDecodeFailed = { decodeFailures.trySend(it) },
    )

    /**
     * Канал, будящий кадровый цикл, когда появляется работа.
     *
     * Цикл спит в [awaitFrameWork], а сигнал шлёт декод тайла с io и сам [updateTiles].
     * CONFLATED, потому что циклу важен только сам факт появления работы.
     *
     * В dispose канал намеренно не закрывается, потому что ждущий получатель получил бы
     * исключение, а цикл и так снимается отменой композиции.
     */
    private val frameWork = Channel<Unit>(Channel.CONFLATED)

    // Последний набор нужных тайлов. Нужен для проверки покрытия подложки.
    private var lastNeeded: Set<TileRect> = emptySet()

    // Подписка для Canvas. Читается внутри DrawScope, поэтому инвалидирует только отрисовку.
    private val _revision = mutableIntStateOf(0)
    val revision: IntState = _revision

    val activeTiles: Map<TileRect, DrawableTile>
        get() = cache.activeTiles
    val fallbackTiles: Map<TileRect, DrawableTile>
        get() = cache.fallbackTiles

    /** Размытая подложка на весь кадр */
    val overview: DrawableTile? = tileSource.overview?.let(::DrawableTile)

    val imageWidth: Float
        get() = tileSource.imageSize.widthF
    val imageHeight: Float
        get() = tileSource.imageSize.heightF

    /** Количество тайлов под текущий viewport */
    val neededTilesCount: Int
        get() = lastNeeded.size

    val pendingTilesCount: Int
        get() = loading.pendingCount
    val loadingTilesCount: Int
        get() = loading.loadingCount
    val toLoadQueueCount: Int
        get() = loading.queuedCount
    val releasePendingCount: Int
        get() = cache.releasePendingCount

    /** Определяет есть ли что делать. */
    val isIdle: Boolean
        get() = loadingTilesCount == 0 &&
            pendingTilesCount == 0 &&
            releasePendingCount == 0 &&
            toLoadQueueCount == 0

    init {
        loading.activeSampleSize = lod.sampleSize
    }

    /**
     * Пересчитывает нужные тайлы под текущий viewport и ставит недостающие в очередь загрузки.
     * При смене `inSampleSize` текущая генерация задач отменяется, а активный слой
     * становится подложкой.
     */
    fun updateTiles(
        scope: CoroutineScope,
        screenW: Float,
        screenH: Float,
        checkedCulling: Boolean,
        checkedLod: Boolean,
    ) {
        val methodStart = SystemClock.elapsedRealtime()
        Trace.beginSection("updateTiles")

        try {
            val viewport = useCases.calculateViewport(
                screenWidth = screenW,
                screenHeight = screenH,
                offset = PanOffset(panZoom.offset.x, panZoom.offset.y),
                scale = panZoom.scale,
            )
            // Уровень считаем раньше набора тайлов, потому что от него зависит размер клетки.
            val sampleSizeChanged = lod.update(panZoom.scale, checkedLod)
            val needed = useCases
                .getVisibleTiles(tileSource.grid, viewport, checkedCulling, lod.sampleSize)
                .toSet()
            lastNeeded = needed

            // Подложку чистим по области, а не по needed, потому что её тайлы с прежнего уровня
            // и по ключам ни с чем не совпадут. При выключенном culling область это всё
            // изображение, поэтому подложка остаётся целиком.
            val fallbackArea = if (checkedCulling) viewport else ImageRect.full(tileSource.imageSize)

            if (sampleSizeChanged) onSampleSizeChanged(fallbackArea)

            val fallbackChanged = cache.cullFallback(fallbackArea)
            loading.cullOutOfViewport(needed)

            val toUnload = cache.activeTiles.keys - needed
            val toLoad = needed.filterNot { it in cache.activeTiles || loading.isTracked(it) }

            logUpdatePlan(needed, toLoad, toUnload)
            unloadTiles(toUnload)

            cache.releaseFallbackIfCovered(needed)

            loading.enqueue(toLoad)
            val launchedNow = loading.launchUpToLimit(scope, needed)
            if (hasFrameWork()) signalFrameWork()

            // Одна инвалидация Canvas на весь updateTiles
            if (sampleSizeChanged || fallbackChanged || toUnload.isNotEmpty()) bumpRevision()

            logUpdateDone(methodStart, toLoad, toUnload, launchedNow)
        } finally {
            Trace.endSection()
        }
    }

    /** Снимает с загрузки и выбрасывает из активного слоя тайлы, ушедшие из viewport. */
    private fun unloadTiles(toUnload: Set<TileRect>) {
        toUnload.forEach { rect ->
            loading.cancelTile(rect)
            cache.removeActive(rect)
        }
    }

    /**
     * Логгирует, сколько тайлов пересчёт собирается загрузить и выгрузить.
     */
    private fun logUpdatePlan(
        needed: Set<TileRect>,
        toLoad: List<TileRect>,
        toUnload: Set<TileRect>,
    ) {
        if (toLoad.isEmpty() && toUnload.isEmpty()) return
        DemoLog.hot {
            "updateTiles: scale=${(panZoom.scale * PERCENT).toInt()}% sampleSize=${lod.sampleSize} " +
                "needed=${needed.size} +${toLoad.size} -${toUnload.size} | " +
                "active=${cache.activeTiles.size} fallback=${cache.fallbackTiles.size} " +
                "pending=${loading.pendingCount} loading=${loading.loadingCount}"
        }
    }

    /**
     * Логгирует, сколько занял пересчёт тайлов и что осталось в кеше и очередях.
     */
    private fun logUpdateDone(
        startMs: Long,
        toLoad: List<TileRect>,
        toUnload: Set<TileRect>,
        launched: Int,
    ) {
        val elapsed = SystemClock.elapsedRealtime() - startMs
        if (elapsed < SLOW_UPDATE_MS && toLoad.isEmpty() && toUnload.isEmpty()) return
        DemoLog.hot {
            "updateTiles: done in ${elapsed}ms | active=${cache.activeTiles.size} " +
                "pending=${loading.pendingCount} loading=${loading.loadingCount} " +
                "launched=$launched toLoadQueue=${loading.queuedCount}"
        }
    }

    /**
     * Ждёт, когда у кадрового цикла появится работа.
     * (Защита от теплового троттлинга)
     */
    suspend fun awaitFrameWork() {
        if (hasFrameWork()) return
        frameWork.receive()
    }

    private fun hasFrameWork(): Boolean =
        loading.pendingCount > 0 || cache.releasePendingCount > 0 || loading.queuedCount > 0

    private fun signalFrameWork() {
        frameWork.trySend(Unit)
    }

    /** Ловит сбой декода. Возвращает причину; вызывающий обязан быть на main. */
    suspend fun awaitDecodeFailure(): Throwable = decodeFailures.receive()

    /**
     * Отдаём все тайлы, что держим, и гасим очередь, если память закончилась.
     */
    fun dropTilesAfterFailure() {
        val dropped = cache.activeTiles.size + cache.fallbackTiles.size
        loading.cancelGeneration()
        cache.dispose()
        lastNeeded = emptySet()
        bumpRevision()
        DemoLog.cold { "нехватка памяти: сброшено $dropped тайлов, очередь декода очищена" }
    }

    /** Зов из frame-loop: добираем отложенные задачи декодирования. */
    fun pumpToLoadQueue(scope: CoroutineScope): Int {
        if (loading.queuedCount == 0) return 0
        return loading.launchUpToLimit(scope, lastNeeded)
    }

    /** Переносит готовые тайлы в активный слой; максимум одна инвалидация на кадр. */
    fun flushPendingTiles() {
        if (loading.pendingCount == 0) return
        val flushStart = SystemClock.elapsedRealtime()
        Trace.beginSection("flushPendingTiles")
        val flushed = try {
            loading.flushPendingInto(cache)
        } finally {
            Trace.endSection()
        }

        val fallbackReleased = cache.releaseFallbackIfCovered(lastNeeded)
        if (flushed > 0 || fallbackReleased) bumpRevision()

        val elapsed = SystemClock.elapsedRealtime() - flushStart
        DemoLog.hot {
            "flush: +$flushed tiles in ${elapsed}ms | active=${cache.activeTiles.size} " +
                "fallback=${cache.fallbackTiles.size} pending=${loading.pendingCount} " +
                "loading=${loading.loadingCount}"
        }
    }

    /** Возврат буферов в пул после выдержки в несколько кадров. */
    fun drainRecyclable(): Int = cache.drainReleasable()

    fun dispose() {
        releaseOverviewLater()
        DemoLog.cold {
            "dispose: active=${cache.activeTiles.size} fallback=${cache.fallbackTiles.size} " +
                "pending=${loading.pendingCount} releasePending=${cache.releasePendingCount} " +
                "loading=${loading.loadingCount}"
        }
        loading.dispose()
        cache.dispose()
        tileSource.close()
        DemoLog.cold { "dispose: done, репозиторий тайлов закрыт" }
    }

    /**
     * Утилизирует битмап подложки с задержкой в [OVERVIEW_RELEASE_DELAY_MS].
     *
     * Сразу звать recycle нельзя, потому что HWUI держит ссылку в display list текущего кадра,
     * а перерисовать этот вьюер уже некому. Задержка даёт кадру дорисоваться.
     */
    private fun releaseOverviewLater() {
        val tile = overview ?: return
        Handler(Looper.getMainLooper()).postDelayed({ tile.release() }, OVERVIEW_RELEASE_DELAY_MS)
    }

    /**
     * Готовит кеш и загрузку к новому уровню детализации.
     *
     * Отменяет текущую генерацию задач,
     * потому что их результат декодируется под старый sampleSize и уже не нужен.
     * Активный слой переносит в подложку, чтобы не было белой вспышки, пока новый LOD догружается.
     *
     * Если активный слой пуст, значит пользователь проскочил несколько уровней одним пинчем.
     * Тогда, если прежняя подложка пересекается с [area], то она сохранится.
     */
    private fun onSampleSizeChanged(area: ImageRect) {
        val staleCount = loading.cancelGeneration()
        loading.activeSampleSize = lod.sampleSize

        if (cache.activeTiles.isNotEmpty()) {
            val promoted = cache.promoteActiveToFallback()
            DemoLog.cold {
                "LOD changed: sampleSize=${lod.sampleSize} | " +
                    "discarded $staleCount pending, promoted $promoted active->fallback"
            }
        } else {
            val keepingFallback = cache.fallbackIntersects(area)
            if (!keepingFallback) cache.dropFallback()
            DemoLog.cold {
                val fallback = if (keepingFallback) "kept=${cache.fallbackTiles.size}" else "dropped"
                "LOD changed: sampleSize=${lod.sampleSize} | " +
                    "discarded $staleCount pending, active empty, fallback $fallback"
            }
        }
    }

    private fun bumpRevision() {
        _revision.intValue += 1
    }

    private companion object {
        const val SLOW_UPDATE_MS = 2L

        // Масштаб целым числом процентов
        const val PERCENT = 100

        /**
         * Задержка перед утилизацией подложки, в миллисекундах.
         *
         * Запас больше, чем живёт display list, в котором подложка могла остаться
         * нарисованной.
         */
        const val OVERVIEW_RELEASE_DELAY_MS = 200L
    }
}
