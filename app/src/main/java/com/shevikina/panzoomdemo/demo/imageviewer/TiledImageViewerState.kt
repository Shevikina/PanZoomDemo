package com.shevikina.panzoomdemo.demo.imageviewer

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.shevikina.panzoomdemo.demo.imageviewer.TiledImageViewerState.Companion.GRACE_FRAMES
import com.shevikina.panzoomdemo.models.states.PanZoomState
import com.shevikina.panzoomdemo.tiling.TilePool
import com.shevikina.panzoomdemo.tiling.TiledImage
import com.shevikina.panzoomdemo.tiling.utils.screenRectToImageRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PanZoomDemo"

private data class TileEntry(val bitmap: Bitmap, val sampleSize: Int)

// Битмап, ожидающий возврата в TilePool. Хранит «возраст в кадрах», чтобы дать
// Skia/HWUI время реально закопировать пиксели в GPU-текстуру по последнему display-list.
// Размер корзины в пуле определяется dimensions самого битмапа — sampleSize хранить не нужно.
private data class DeferredRecycle(val frameId: Int, val bitmap: Bitmap)

class TiledImageViewerState(
    private val tiledImage: TiledImage,
    private val pool: TilePool,
) {
    val panZoom = PanZoomState()

    // Шаг 7: обычные LinkedHashMap вместо SnapshotStateMap.
    // SnapshotStateMap дёргает observer на каждый put (`activeTiles[rect] = bmp`), а
    // flushPendingTiles переносит 1-20 тайлов за тик — это даёт 1-20 Canvas-инвалидаций/кадр.
    // Сейчас Canvas подписывается через [revisionState] один раз, мутации map'ов не
    // дёргают наблюдателя; bumpRevision() вызывается ОДИН РАЗ в конце batched operation.
    //
    // ВАЖНО: эти map'ы изменяются СТРОГО с main-thread (методы класса). Внешний код
    // читает только. Никаких write-операций снаружи.
    val activeTiles: LinkedHashMap<Rect, Bitmap> = LinkedHashMap()

    // Подложка из тайлов ПРЕДЫДУЩЕГО LOD. На смене sampleSize текущие activeTiles переезжают
    // сюда и продолжают рисоваться ПОД новыми тайлами, пока новый LOD не покроет viewport.
    // Без подложки на каждой границе LOD пользователь видит мгновенный «blank flash».
    // Бутыль с одним горлышком: одновременно живёт только один уровень fallback.
    val fallbackTiles: LinkedHashMap<Rect, Bitmap> = LinkedHashMap()

    // Внутренний map хранит дополнительно sampleSize, с которым декодирован тайл
    private val activeTileEntries = ConcurrentHashMap<Rect, TileEntry>()
    private val fallbackTileEntries = ConcurrentHashMap<Rect, TileEntry>()

    // Подписка для Canvas: bumpRevision() вызывается один раз в конце batched-mutation.
    // Canvas читает .intValue ВНУТРИ DrawScope — это вызовет invalidate-draw без recompose.
    private val _revision = mutableIntStateOf(0)
    val revisionState: IntState = _revision

    private fun bumpRevision() {
        _revision.intValue = _revision.intValue + 1
    }

    // Per-tile loading jobs
    private val loadingJobs = ConcurrentHashMap<Rect, Job>()

    // Скоуп текущей «LOD-генерации». Все decode-корутины запускаются под ним.
    // На смене sampleSize один `lodScope.cancel()` отменяет сотни задач за O(1) вместо
    // `loadingJobs.values.forEach { it.cancel() }` (давал ~70 мс jank при 900+ in-flight).
    // SupervisorJob: дочерние ошибки/отмены не валят соседей, что согласуется с per-tile semantics.
    // Параметр parent — внешний tileScope из composable: его cancel (dispose) каскадно гасит наш.
    private var lodScope: CoroutineScope? = null

    // Очередь rect'ов, которые НУЖНО загрузить, но мы ещё не запустили под них корутины.
    // Без этого `toLoad.forEach { scope.launch }` на extreme zoom-out (1000+ тайлов нужно)
    // тратит 80-450 ms главного потока за один updateTiles → жирный jank.
    // Дренируется по [MAX_LAUNCHES_PER_TICK] за вызов из updateTiles И frame-loop'а.
    private val toLoadQueue = ArrayDeque<Rect>()
    val toLoadQueueCount: Int get() = toLoadQueue.size

    // Тайлы, декодированные на IO-потоке, ещё не внесённые в State.
    // IO-потоки только пишут сюда; main thread читает и сбрасывает в activeTiles.
    // ConcurrentHashMap — без State-нотификаций, не вызывает рекомпозицию.
    private val pendingEntries = ConcurrentHashMap<Rect, TileEntry>()

    @Volatile
    private var lastSampleSize = 1

    // Дебаунс смены LOD: чтобы не реагировать на мимолётное пересечение порога во время
    // быстрого pinch-зум-аут жеста, требуем, чтобы желаемый sampleSize удержался N
    // последовательных вызовов updateTiles. Это страховка поверх гистерезиса в
    // [TiledImage.sampleSizeForScale]: гистерезис убирает дрожание у границы, а дебаунс —
    // ложные срабатывания при «пролёте» через несколько LOD за один жест.
    private var pendingNewSampleSize = 1
    private var pendingSampleSizeStreak = 0

    // Последний вычисленный набор rect'ов viewport — для проверки покрытия fallback'а.
    // Доступ строго main-thread: пишется в updateTiles, читается в flushPendingTiles.
    @Volatile
    private var lastNeeded: Set<Rect> = emptySet()

    // Очередь «надгробий»: битмапы, удалённые из activeTiles, ждут [GRACE_FRAMES] кадров
    // прежде чем уйти в пул. Без этой задержки IO-поток мог бы перезаписать пиксели
    // через inBitmap, пока Skia ещё держит их в display-list — отсюда «растянутые»
    // тайлы и тайлы «не своего» содержимого, наблюдавшиеся после шага 3.
    // Доступ строго main-thread (frame-loop + updateTiles).
    private val recyclePending = ArrayDeque<DeferredRecycle>()
    private var frameCounter = 0

    // Размеры исходного изображения — экспонированы для clamp в PanZoomState.setBounds.
    val imageWidth: Float get() = tiledImage.fullWidth.toFloat()
    val imageHeight: Float get() = tiledImage.fullHeight.toFloat()

    // Счётчики для периодического логирования из frame-loop
    val pendingTilesCount: Int get() = pendingEntries.size
    val loadingTilesCount: Int get() = loadingJobs.size
    val recyclePendingCount: Int get() = recyclePending.size

    /**
     * Пересчитывает нужные тайлы для текущего viewport и запускает их загрузку.
     *
     * При изменении inSampleSize все in-flight задачи и pending-очередь отменяются,
     * а тайлы с устаревшим разрешением выгружаются и перезапрашиваются.
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

        // flushPendingTiles() здесь больше НЕ вызываем — единственный источник истины
        // для переноса pending→active это frame-loop (withFrameMillis в TiledImageViewer).
        // Двойной flush приводил к лишним мутациям SnapshotStateMap и расширял время updateTiles.
        // Готовые тайлы, ещё не попавшие в activeTiles, исключаются из toLoad через
        // `- pendingEntries.keys` ниже, так что повторного декодирования не возникает.

        val viewportInImage = if (checkedCulling) {
            screenRectToImageRect(screenW, screenH, panZoom.offset, panZoom.scale)
        } else {
            RectF(0f, 0f, tiledImage.fullWidth.toFloat(), tiledImage.fullHeight.toFloat())
        }

        // Цель LOD с учётом гистерезиса: пороги up/down раздвинуты, дрожание у границы не сработает.
        val desiredSampleSize = if (checkedLod) {
            tiledImage.sampleSizeForScale(panZoom.scale, lastSampleSize)
        } else {
            1
        }

        // Дебаунс: применяем смену только если desiredSampleSize удерживается несколько
        // подряд идущих updateTiles. На быстром pinch-zoom пользователь часто «пролетает»
        // через ss=2 в ss=4, а потом возвращается — без дебаунса каждый из этих пролётов
        // вызывал бы full-flush activeTiles → fallback → перезаливку.
        val newSampleSize: Int = when {
            desiredSampleSize == lastSampleSize -> {
                // Steady-state. Не логируем (это >95 % invocation-ов, спамить нет смысла).
                pendingSampleSizeStreak = 0
                lastSampleSize
            }
            desiredSampleSize == pendingNewSampleSize -> {
                pendingSampleSizeStreak++
                if (pendingSampleSizeStreak >= SAMPLE_SIZE_STABLE_INVOCATIONS) {
                    pendingSampleSizeStreak = 0
                    desiredSampleSize
                } else {
                    Log.d(
                        TAG,
                        "LOD debounce: hold ss=$lastSampleSize, desired=$desiredSampleSize streak=$pendingSampleSizeStreak/$SAMPLE_SIZE_STABLE_INVOCATIONS"
                    )
                    lastSampleSize
                }
            }
            else -> {
                Log.d(
                    TAG,
                    "LOD debounce: reset, desired=$desiredSampleSize (was pending=$pendingNewSampleSize streak=$pendingSampleSizeStreak)"
                )
                pendingNewSampleSize = desiredSampleSize
                pendingSampleSizeStreak = 1
                lastSampleSize
            }
        }

        // needed считаем ДО блока смены LOD: блок использует его, чтобы решить судьбу fallback'а
        // (сохранять старый или дропать) при «прокручивании» pinch через несколько LOD за раз.
        val needed = tiledImage.visibleTileRects(viewportInImage).toSet()
        lastNeeded = needed

        val sampleSizeChanged = newSampleSize != lastSampleSize
        if (sampleSizeChanged) {
            lastSampleSize = newSampleSize
            // Отменяем всю «LOD-генерацию» одним cancel() вместо forEach по 900+ Job'ам
            // (раньше давало 70-180 мс jank). Следующий launch создаст свежий scope.
            lodScope?.cancel()
            lodScope = null
            loadingJobs.clear()
            // Тайлы в pendingEntries декодированы со старым inSampleSize — возвращаем
            // буферы в TilePool (корзина выбирается по dimensions самого битмапа). Гонки нет:
            // pending-битмапы никогда не попадали в activeTiles и не могут быть в display-list Skia.
            val staleCount = pendingEntries.size
            pendingEntries.values.forEach { pool.recycle(it.bitmap) }
            pendingEntries.clear()
            // Очередь отложенных запусков нацелена на старый sampleSize. Чистим, чтобы не
            // запустить устаревшие задачи следующим pump'ом. Актуальный toLoad перевычислится
            // ниже с новым sampleSize.
            toLoadQueue.clear()

            if (activeTiles.isNotEmpty()) {
                // Базовый кейс: есть что положить в подложку. Старый fallback (если был)
                // вытесняется новым через recyclePending — на экране перекрывается без артефактов.
                val prevFallbackSize = fallbackTiles.size
                fallbackTiles.values.forEach { bmp ->
                    recyclePending.addLast(DeferredRecycle(frameCounter, bmp))
                }
                fallbackTiles.clear()
                fallbackTileEntries.clear()
                val promoted = activeTiles.size
                fallbackTiles.putAll(activeTiles)
                fallbackTileEntries.putAll(activeTileEntries)
                activeTiles.clear()
                activeTileEntries.clear()
                Log.d(
                    TAG,
                    "updateTiles: LOD changed → sampleSize=$newSampleSize | discarded $staleCount pending, promoted $promoted active→fallback, dropped $prevFallbackSize old-fallback"
                )
            } else {
                // active пуст. Это случай «прокручивания» pinch через 2+ LOD за раз: тайлы предыдущего
                // LOD не успели докачаться, новых ещё нет. Сохраняем СТАРЫЙ fallback (если он есть и
                // хоть один его rect пересекается с текущим needed — иначе подложка визуально бесполезна).
                val keepingFallback = fallbackTiles.isNotEmpty() && fallbackTiles.keys.any { it in needed }
                if (!keepingFallback && fallbackTiles.isNotEmpty()) {
                    fallbackTiles.values.forEach { bmp ->
                        recyclePending.addLast(DeferredRecycle(frameCounter, bmp))
                    }
                    fallbackTiles.clear()
                    fallbackTileEntries.clear()
                }
                activeTileEntries.clear()
                Log.d(
                    TAG,
                    "updateTiles: LOD changed → sampleSize=$newSampleSize | discarded $staleCount pending, active empty, fallback ${if (keepingFallback) "kept=${fallbackTiles.size}" else "dropped"}"
                )
            }
        }

        // Culling для in-flight задач и pending-тайлов: всё, что ушло за пределы viewport,
        // отменяем/recycle-им. Без этого loadingJobs распухает до тысяч задач при панорамировании,
        // а decodeTile (сериализован Mutex'ом) ждёт десятки секунд в очереди.
        val toCancelJobs = loadingJobs.keys - needed
        toCancelJobs.forEach { key -> loadingJobs.remove(key)?.cancel() }
        val stalePending = pendingEntries.keys - needed
        stalePending.forEach { key ->
            val entry = pendingEntries.remove(key) ?: return@forEach
            pool.recycle(entry.bitmap)
        }

        // Cull fallback по needed: rects, которые ушли из viewport после pan, бесполезны
        // визуально и держат память. Особенно актуально при «удержанном fallback» из
        // прошлого блока — без чистки он лежит мёртвым грузом, пока активный слой не
        // покроет весь viewport (тогда maybeReleaseFallback его сметёт целиком).
        val staleFallback = fallbackTiles.keys - needed
        if (staleFallback.isNotEmpty()) {
            staleFallback.forEach { key ->
                fallbackTileEntries.remove(key)
                fallbackTiles.remove(key)?.let { bmp ->
                    recyclePending.addLast(DeferredRecycle(frameCounter, bmp))
                }
            }
            Log.d(TAG, "fallback culled: ${staleFallback.size} rects out of viewport (remain=${fallbackTiles.size})")
        }

        // staleByScale больше не нужен: на смене LOD весь activeTiles уезжает в fallbackTiles,
        // так что в activeTiles всегда тайлы только текущего sampleSize.
        val toUnload = activeTiles.keys - needed
        // Исключаем тайлы уже в pendingEntries: они будут перенесены в activeTiles при flush
        val toLoad = needed - activeTiles.keys - loadingJobs.keys - pendingEntries.keys

        if (toLoad.isNotEmpty() || toUnload.isNotEmpty() || toCancelJobs.isNotEmpty() || stalePending.isNotEmpty()) {
            Log.d(
                TAG,
                "updateTiles: scale=%.2f sampleSize=$newSampleSize needed=${needed.size} +${toLoad.size} -${toUnload.size} cancel=${toCancelJobs.size} stalePending=${stalePending.size} | active=${activeTiles.size} fallback=${fallbackTiles.size} pending=${pendingEntries.size} loading=${loadingJobs.size}".format(
                    panZoom.scale
                )
            )
        }

        toUnload.forEach { key ->
            loadingJobs.remove(key)?.cancel()
            // pending-битмапы не успели попасть в activeTiles → display-list Skia
            // их не видит → безопасно возвращать в пул сразу.
            pendingEntries.remove(key)?.let { entry ->
                pool.recycle(entry.bitmap)
            }
            activeTileEntries.remove(key)
            activeTiles.remove(key)?.let { bmp ->
                // Откладываем возврат в пул: текущий кадр мог уже записать bmp
                // в display-list, перезапись пикселей через inBitmap дала бы артефакты.
                recyclePending.addLast(DeferredRecycle(frameCounter, bmp))
            }
        }

        // Снимок sampleSize на момент enqueue — передаём явно в decodeTile.
        // scaleSnapshot больше не нужен: гистерезис/дебаунс уже учтены при выборе newSampleSize выше.
        val scheduledSampleSize = newSampleSize

        // Если по итогу updateTiles активный слой уже покрыл needed — fallback не нужен.
        // Бывает на быстрых LOD-вверх (zoom out → меньше тайлов нужно → новый LOD догрузил всё).
        maybeReleaseFallback()

        // Не запускаем все toLoad сразу: каждое scope.launch стоит ~100 μs на main thread,
        // а на extreme zoom-out (needed=1000+) это даёт jank 80-450 ms за один updateTiles.
        // Кладём в [toLoadQueue], дренируем первый батч сейчас; frame-loop добирёт остальное.
        toLoadQueue.addAll(toLoad)
        val launchedNow = launchQueuedTilesUpToLimit(scope, scheduledSampleSize)

        // Шаг 7: одна инвалидация Canvas на весь updateTiles вместо ~20 (по числу мутаций).
        // Условие совпадает с условием "что-то поменялось в activeTiles/fallbackTiles":
        // sampleSizeChanged всегда трогает оба map'а; staleFallback и toUnload могут трогать
        // отдельно. Пропускаем bump если updateTiles был чисто read-only (рекомпозиций нет).
        if (sampleSizeChanged || staleFallback.isNotEmpty() || toUnload.isNotEmpty()) {
            bumpRevision()
        }

        Trace.endSection()
        val elapsed = SystemClock.elapsedRealtime() - methodStart
        if (elapsed >= 2L || toLoad.isNotEmpty() || toUnload.isNotEmpty()) {
            Log.d(
                TAG,
                "updateTiles: done in ${elapsed}ms | active=${activeTiles.size} pending=${pendingEntries.size} loading=${loadingJobs.size} launched=$launchedNow toLoadQueue=${toLoadQueue.size}"
            )
        }
    }

    /**
     * Ленивая инициализация [lodScope]. Пересоздаёт скоуп, если предыдущий был отменён
     * (после смены LOD или дисплея). Привязывает к `parent` через SupervisorJob — отмена
     * parent (dispose composable) гасит lodScope автоматически.
     */
    private fun obtainLodScope(parent: CoroutineScope): CoroutineScope {
        val existing = lodScope
        if (existing != null && existing.isActive) return existing
        val fresh = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        lodScope = fresh
        return fresh
    }

    /**
     * Снимает с [toLoadQueue] до [MAX_LAUNCHES_PER_TICK] rect'ов и запускает под каждый
     * IO-задачу декодирования. Пропускает rect'ы, ставшие неактуальными
     * (уже загружены / уже в работе / вышли за viewport).
     *
     * @return сколько задач реально запустили.
     */
    private fun launchQueuedTilesUpToLimit(scope: CoroutineScope, sampleSize: Int): Int {
        var launched = 0
        val needed = lastNeeded
        val targetScope = obtainLodScope(scope)
        while (launched < MAX_LAUNCHES_PER_TICK && toLoadQueue.isNotEmpty()) {
            val rect = toLoadQueue.removeFirst()
            // Защита от race: между постановкой в очередь и запуском rect мог стать неактуальным.
            if (rect !in needed) continue
            if (loadingJobs.containsKey(rect)) continue
            if (pendingEntries.containsKey(rect)) continue
            if (activeTiles.containsKey(rect)) continue
            loadingJobs[rect] = targetScope.launch(Dispatchers.IO) {
                // Re-check ДО захвата декодера: пока задача ждала своей очереди (или в pumpQueue
                // полежала кадры), главный поток мог принять смену LOD и сдвинуть lastSampleSize.
                ensureActive()
                if (lastSampleSize != sampleSize) {
                    loadingJobs.remove(rect)
                    return@launch
                }
                val bmp = tiledImage.decodeTile(
                    rect = rect,
                    sampleSize = sampleSize,
                ) ?: return@launch
                // Финальная проверка: между завершением decodeTile и записью в pendingEntries
                // job мог быть отменён. Без чека «осиротевший» битмап со старым sampleSize
                // пролился бы в activeTiles. ensureActive бросит CancellationException.
                ensureActive()
                pendingEntries[rect] = TileEntry(bmp, sampleSize)
                loadingJobs.remove(rect)
            }
            launched++
        }
        return launched
    }

    /**
     * Зов из frame-loop'а композабла: продолжаем запускать отложенные задачи декодирования.
     * Использует **текущий** [lastSampleSize] — если он сменился после постановки в очередь,
     * worker re-check внутри [launchQueuedTilesUpToLimit] отфильтрует устаревшие задачи.
     *
     * @return сколько задач реально запустили в этом кадре.
     */
    fun pumpToLoadQueue(scope: CoroutineScope): Int {
        if (toLoadQueue.isEmpty()) return 0
        return launchQueuedTilesUpToLimit(scope, lastSampleSize)
    }

    /**
     * Возвращает в [TilePool] битмапы из [recyclePending], которые отлежали ≥ [GRACE_FRAMES]
     * кадров с момента удаления из [activeTiles]. Гарантия: display-list Skia уже не ссылается
     * на их пиксели — безопасно перезаписать через `inBitmap`.
     *
     * Возвращает количество фактически утилизированных битмапов в текущем кадре.
     */
    fun drainRecyclable(): Int {
        frameCounter++
        var drained = 0
        while (recyclePending.isNotEmpty() && frameCounter - recyclePending.first().frameId >= GRACE_FRAMES) {
            val entry = recyclePending.removeFirst()
            pool.recycle(entry.bitmap)
            drained++
        }
        return drained
    }

    /**
     * Переносит все готовые тайлы из [pendingEntries] в [activeTiles] за одно обновление State.
     *
     * Вызывается с main thread: из frame-sync цикла в composable.
     * Итого: максимум одна рекомпозиция на Choreographer-кадр.
     */
    fun flushPendingTiles() {
        if (pendingEntries.isEmpty()) return
        val flushStart = SystemClock.elapsedRealtime()
        Trace.beginSection("flushPendingTiles")
        var flushed = 0
        val iterator = pendingEntries.entries.iterator()
        while (iterator.hasNext()) {
            val (rect, entry) = iterator.next()
            iterator.remove()
            activeTileEntries[rect] = entry
            activeTiles[rect] = entry.bitmap
            flushed++
        }
        Trace.endSection()
        // После долива новых тайлов в активный слой — пробуем отпустить fallback.
        // Когда current LOD покрывает viewport, подложка под ним становится лишней.
        // maybeReleaseFallback сам bump-нет revision, если что-то отпустил.
        maybeReleaseFallback()
        // Один bump на весь batch перенесённых тайлов (вместо `flushed` инвалидаций).
        if (flushed > 0) bumpRevision()
        val elapsed = SystemClock.elapsedRealtime() - flushStart
        Log.d(
            TAG,
            "flush: +$flushed tiles in ${elapsed}ms | active=${activeTiles.size} fallback=${fallbackTiles.size} pending=${pendingEntries.size} loading=${loadingJobs.size}"
        )
    }

    /**
     * Если активный слой полностью покрывает viewport — отпускает fallback (битмапы уезжают
     * в [recyclePending], откуда через [GRACE_FRAMES] кадров уйдут в пул).
     */
    private fun maybeReleaseFallback() {
        if (fallbackTiles.isEmpty()) return
        val needed = lastNeeded
        if (needed.isEmpty()) return
        if (!activeTiles.keys.containsAll(needed)) return
        val released = fallbackTiles.size
        fallbackTiles.values.forEach { bmp ->
            recyclePending.addLast(DeferredRecycle(frameCounter, bmp))
        }
        fallbackTiles.clear()
        fallbackTileEntries.clear()
        bumpRevision()
        Log.d(TAG, "fallback released: $released tiles (current LOD covers viewport)")
    }

    fun dispose() {
        toLoadQueue.clear()
        // Один cancel вместо forEach — заодно отменяет всё, что в полёте.
        lodScope?.cancel()
        lodScope = null
        loadingJobs.clear()
        pendingEntries.values.forEach { it.bitmap.recycle() }
        pendingEntries.clear()
        recyclePending.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        recyclePending.clear()
        activeTileEntries.clear()
        activeTiles.values.forEach { it.recycle() }
        activeTiles.clear()
        fallbackTileEntries.clear()
        fallbackTiles.values.forEach { if (!it.isRecycled) it.recycle() }
        fallbackTiles.clear()
        tiledImage.close()
        pool.clear()
    }

    private companion object {
        // Сколько кадров битмап должен «отлежать» прежде чем уйти в пул.
        // 3 кадра ≈ 48 мс при 60 Гц — запас на триплбуфер HWUI/SurfaceFlinger:
        // при 2 кадрах оставшийся в очереди дисплея кадр N-2 ещё мог ссылаться на пиксели,
        // и перезапись через inBitmap давала визуальные артефакты.
        const val GRACE_FRAMES = 3

        // Сколько подряд идущих updateTiles должны хотеть один и тот же новый sampleSize,
        // прежде чем мы реально переключим LOD. 3 ≈ 50 мс на 60 Гц (один short pinch-tick).
        const val SAMPLE_SIZE_STABLE_INVOCATIONS = 3

        // Лимит scope.launch на один тик (updateTiles ИЛИ pump из frame-loop).
        // Каждый launch стоит ~100 μs main-thread; на extreme zoom-out с 1000+ tile'ами
        // запуск всех сразу даёт jank 80-450 мс. 64 launch'а ≈ 6 мс main-thread за тик
        // и 64×16 мс = 1 секунда wall-time для полного дрена 3500-тайловой очереди.
        const val MAX_LAUNCHES_PER_TICK = 64
    }
}

@Composable
fun rememberTiledImageViewerState(assetName: String): TiledImageViewerState {
    val context = LocalContext.current
    val pool = remember { TilePool() }

    val tiledImage = remember(assetName) {
        TiledImage(context.assets.open(assetName), pool)
    }
    val state = remember(tiledImage) {
        TiledImageViewerState(tiledImage, pool)
    }

    DisposableEffect(tiledImage) {
        onDispose { state.dispose() }
    }

    return state
}
