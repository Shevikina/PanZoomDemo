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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
                        Log.d(
                            TAG,
                            "frameLoop: ${frameCount}fps | flushed=$totalFlushed/s recycled=$totalRecycled/s pumped=$totalPumped/s active=${state.activeTiles.size} pending=${state.pendingTilesCount} loading=${state.loadingTilesCount} recyclePending=${state.recyclePendingCount} toLoadQueue=${state.toLoadQueueCount}"
                        )
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
            panZoom = state.panZoom,
            useGraphicsLayer = useGraphicsLayer,
        )
    }
}

@Composable
private fun TiledImageViewerContent(
    activeTiles: Map<Rect, Bitmap>,
    fallbackTiles: Map<Rect, Bitmap>,
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

    val gestureModifier = Modifier.pointerInput(panZoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = false)
                val uptimeMillis = event.changes.first().uptimeMillis

                panZoom.onZoom(zoom)
                panZoom.onPan(pan, uptimeMillis, centroid)

                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })

            panZoom.fling(scope)
        }
    }

    if (useGraphicsLayer) {
        // ✅ Хорошо: читаем State в DrawScope (Drawing-фаза) — тот же принцип, что graphicsLayer { }
        //    Рекомпозиции = 0 под жестом, Canvas перерисовывается без Composition
        Canvas(
            modifier = modifier
                .fillMaxSize()
                .then(gestureModifier)
        ) {
            val scale = panZoom.scale
            val offset = panZoom.offset
            // Сначала fallback (тайлы предыдущего LOD), сверху — current. На границах LOD
            // подложка маскирует blank-зоны, пока новый LOD догружается; когда current
            // полностью покрыл viewport, состояние сбрасывает fallback в drainRecyclable.
            drawTiles(fallbackTiles, scale, offset)
            drawTiles(activeTiles, scale, offset)
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
            drawTiles(fallbackTiles, scale, offset)
            drawTiles(activeTiles, scale, offset)
        }
    }
}

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
            )
        )
    }
}
