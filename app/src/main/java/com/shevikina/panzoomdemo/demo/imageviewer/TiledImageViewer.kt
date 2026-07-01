package com.shevikina.panzoomdemo.demo.imageviewer

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shevikina.panzoomdemo.models.states.PanZoomState
import kotlinx.coroutines.flow.conflate

private const val TAG = "PanZoomDemo"

@Composable
fun TiledImageViewer(
    assetName: String,
    checkedCulling: Boolean,
    checkedLod: Boolean,
    useGraphicsLayer: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberTiledImageViewerState(assetName)
    // Стабильный scope: не отменяется при рестарте LaunchedEffect на каждом событии жеста
    val tileScope = rememberCoroutineScope()

    // Счётчик рекомпозиций TiledImageViewer.
    // SideEffect запускается после каждой успешной рекомпозиции (не является её причиной).
    val recomposeCount = remember { intArrayOf(0) }
    SideEffect {
        recomposeCount[0]++
        Log.d(
            TAG,
            "recompose[TiledImageViewer] #${recomposeCount[0]} | activeTiles=${state.activeTiles.size}"
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        // Регистрируем границы viewport/изображения в PanZoomState, чтобы offset не уезжал
        // за пределы видимости. Без этого жест/fling могли увести картинку полностью за экран —
        // visibleTileRects возвращал пустой список (needed=0), и activeTiles оказывался пуст
        // ещё ДО смены LOD, обнуляя смысл fallback-слоя из шага 4.
        LaunchedEffect(state, screenW, screenH) {
            state.panZoom.setBounds(screenW, screenH, state.imageWidth, state.imageHeight)
        }

        // snapshotFlow считывает panZoom.scale/offset внутри корутины, а не в Composition-фазе.
        // При жесте LaunchedEffect НЕ рестартуется и рекомпозиция НЕ происходит.
        // Прежний вариант LaunchedEffect(panZoom.scale, panZoom.offset, ...) вызывал
        // рекомпозицию ~60 раз/сек на каждое движение пальца.
        //
        // conflate() пропускает все промежуточные значения, пока updateTiles ещё работает,
        // оставляя только последнее. На быстром жесте это снижает запуск updateTiles
        // с ~60/сек до ~1/кадр и снимает наслоение тяжёлой работы на жест.
        LaunchedEffect(state, checkedCulling, checkedLod) {
            snapshotFlow { state.panZoom.scale to state.panZoom.offset }
                .conflate()
                .collect {
                    state.updateTiles(
                        scope = tileScope,
                        screenW = screenW,
                        screenH = screenH,
                        checkedCulling = checkedCulling,
                        checkedLod = checkedLod,
                    )
                }
        }

        // Сбрасывает pendingEntries → activeTiles максимум раз в кадр.
        // Нужен когда жест завершён (snapshotFlow не эмитит), но тайлы ещё догружаются.
        // Гарантирует: не более одной рекомпозиции на Choreographer-кадр.
        // Дополнительно логирует: FPS цикла, кол-во сброшенных тайлов, состояние очередей.
        LaunchedEffect(state) {
            var frameCount = 0
            var totalFlushed = 0
            var totalRecycled = 0
            var totalPumped = 0
            var lastReportMs = -1L

            while (true) {
                withFrameMillis { frameMs ->
                    val pendingBefore = state.pendingTilesCount
                    state.flushPendingTiles()
                    val flushed = pendingBefore - state.pendingTilesCount

                    // Отложенный возврат битмапов в пул: тайлы, удалённые из activeTiles
                    // в предыдущих кадрах, отдаём в TilePool только после GRACE_FRAMES.
                    // Этим закрывается гонка с render-thread Skia (artifacts от перезаписи inBitmap).
                    val recycled = state.drainRecyclable()

                    // Батчирование toLoad: updateTiles запускает максимум MAX_LAUNCHES_PER_TICK
                    // корутин за раз. Остальное добираем здесь по 64 в кадр, чтобы не получить
                    // jank на extreme zoom-out, где в очереди может оказаться 1000+ rect'ов.
                    val pumped = state.pumpToLoadQueue(tileScope)

                    frameCount++
                    totalFlushed += flushed
                    totalRecycled += recycled
                    totalPumped += pumped

                    if (lastReportMs < 0L) lastReportMs = frameMs
                    val elapsed = frameMs - lastReportMs
                    if (elapsed >= 1000L) {
                        // Шаг 7: пропускаем лог, если в окне 1 сек НИЧЕГО не происходило.
                        // Иначе на чистом idle (после fling, тайлы загружены) сыплется по 1
                        // Log.d/сек впустую → лишний main-thread tick раз в сек.
                        val idle = totalFlushed == 0 && totalRecycled == 0 && totalPumped == 0 &&
                            state.loadingTilesCount == 0 && state.pendingTilesCount == 0 &&
                            state.recyclePendingCount == 0 && state.toLoadQueueCount == 0
                        if (!idle) {
                            Log.d(
                                TAG,
                                "frameLoop: ${frameCount}fps | flushed=$totalFlushed/s recycled=$totalRecycled/s pumped=$totalPumped/s active=${state.activeTiles.size} pending=${state.pendingTilesCount} loading=${state.loadingTilesCount} recyclePending=${state.recyclePendingCount} toLoadQueue=${state.toLoadQueueCount}"
                            )
                        }
                        frameCount = 0
                        totalFlushed = 0
                        totalRecycled = 0
                        totalPumped = 0
                        lastReportMs = frameMs
                    }
                }
            }
        }

        TiledImageViewerContent(
            activeTiles = state.activeTiles,
            fallbackTiles = state.fallbackTiles,
            revisionState = state.revisionState,
            panZoom = state.panZoom,
            useGraphicsLayer = useGraphicsLayer,
        )
    }
}

@Composable
private fun TiledImageViewerContent(
    activeTiles: Map<Rect, Bitmap>,
    fallbackTiles: Map<Rect, Bitmap>,
    revisionState: IntState,
    panZoom: PanZoomState,
    useGraphicsLayer: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // Счётчик рекомпозиций TiledImageViewerContent.
    // Должен расти ТОЛЬКО когда меняется activeTiles или переключается useGraphicsLayer.
    // Если растёт синхронно с жестом — значит State читается в Composition-фазе.
    val recomposeCount = remember { intArrayOf(0) }
    SideEffect {
        recomposeCount[0]++
        Log.d(
            TAG,
            "recompose[Content] #${recomposeCount[0]} | tiles=${activeTiles.size} graphicsLayer=$useGraphicsLayer"
        )
    }

    // Шаг 7: ключ Unit вместо panZoom. panZoom — стабильная ссылка (создаётся внутри remember-state'а),
    // никогда не меняется в lifetime composable. С ключом panZoom Compose обязан сверять reference на
    // каждой рекомпозиции и держать pipe-restart на случай смены — на практике это никогда не
    // срабатывает, но ест чуть-чуть бюджета. С Unit pointer-pipe настраивается один раз.
    val gestureModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = false)
                val uptimeMillis = event.changes.first().uptimeMillis

                panZoom.onZoom(zoom, centroid)
                panZoom.onPan(pan, uptimeMillis, centroid)

                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })

            panZoom.fling(scope)
        }
    }

    if (useGraphicsLayer) {
        // ✅ Настоящая late transformation: pan/zoom применяет GPU через матрицу graphicsLayer.
        //    Canvas рисует тайлы один раз в image-coords (rect.left, rect.top, без scale/offset);
        //    при жесте Compose обновляет только translation/scale в графическом слое — Canvas
        //    draw-list НЕ перезаписывается, RenderNode переиспользуется. Это даёт:
        //      - 0 рекомпозиций под жестом (state читается в Layer-phase, не в Composition);
        //      - 0 перерисовок Canvas от fling-затухания (state читается на render thread);
        //      - O(GPU матрица) vs O(N tiles) на CPU при панорамировании.
        //    transformOrigin(0,0) — pivot в image (0,0): `screen = image * scale + translation`.
        //
        //    ВАЖНО: gestureModifier стоит В ЧЕЙНЕ ПЕРЕД graphicsLayer.
        //    `graphicsLayer` при hit-test делает inverse-transform координат для inner-модификаторов:
        //    при scale=0.4 точка касания (500,1000) становится (1250,2500) — за пределами
        //    layout-bounds, hit-test проваливается, событие теряется. Если pointerInput стоит
        //    ВНЕ слоя — он принимает события в screen-coords и работает корректно.
        Canvas(
            modifier = modifier
                .fillMaxSize()
                .then(gestureModifier)
                .graphicsLayer {
                    translationX = panZoom.offset.x
                    translationY = panZoom.offset.y
                    scaleX = panZoom.scale
                    scaleY = panZoom.scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            // Чтение revisionState.intValue ВНУТРИ DrawScope подписывает только drawing-phase.
            // Когда revision bump-нут (один раз на batched flush/LOD/cull), Canvas инвалидирует
            // ТОЛЬКО draw-list, без recompose. activeTiles/fallbackTiles теперь обычные LinkedHashMap'ы —
            // их прямые мутации не дёргают observer'а.
            @Suppress("UNUSED_VARIABLE")
            val rev = revisionState.intValue
            // Тайлы рисуются в image-coords. Никаких scale/offset/умножений per-tile.
            // Сначала fallback (тайлы предыдущего LOD), сверху — current.
            drawTilesAtImageCoords(fallbackTiles)
            drawTilesAtImageCoords(activeTiles)
        }
    } else {
        // ❌ Плохо: читаем State в теле composable (Composition-фаза)
        //    → рекомпозиция на каждый MOVE (~60/сек), Layout Inspector покажет счётчик
        val scale = panZoom.scale
        val offset = panZoom.offset
        Canvas(
            modifier = modifier
                .fillMaxSize()
                .then(gestureModifier)
        ) {
            // Та же подписка на revision: с LinkedHashMap'ами никто иначе не обновит Canvas.
            @Suppress("UNUSED_VARIABLE")
            val rev = revisionState.intValue
            drawTiles(fallbackTiles, scale, offset)
            drawTiles(activeTiles, scale, offset)
        }
    }
}

/**
 * Старая «плохая» отрисовка — для ветки [useGraphicsLayer]=false. Каждый тайл
 * сам вычисляет screen-coords через scale/offset на CPU. Skia пересобирает draw-list
 * на каждый кадр жеста, потому что DrawScope наблюдает за State.
 */
private fun DrawScope.drawTiles(
    tiles: Map<Rect, Bitmap>,
    scale: Float,
    offset: androidx.compose.ui.geometry.Offset,
) {
    tiles.forEach { (rect, bmp) ->
        drawImage(
            image = bmp.asImageBitmap(),
            dstOffset = IntOffset(
                x = (rect.left * scale + offset.x).toInt(),
                y = (rect.top * scale + offset.y).toInt(),
            ),
            dstSize = IntSize(
                width = (rect.width() * scale).toInt(),
                height = (rect.height() * scale).toInt(),
            ),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/**
 * Отрисовка в image-coords для ветки [useGraphicsLayer]=true. scale/offset применяются
 * GPU через [Modifier.graphicsLayer] — RenderNode не пересобирается, draw-list стабилен.
 */
private fun DrawScope.drawTilesAtImageCoords(tiles: Map<Rect, Bitmap>) {
    tiles.forEach { (rect, bmp) ->
        drawImage(
            image = bmp.asImageBitmap(),
            dstOffset = IntOffset(rect.left, rect.top),
            dstSize = IntSize(rect.width(), rect.height()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
