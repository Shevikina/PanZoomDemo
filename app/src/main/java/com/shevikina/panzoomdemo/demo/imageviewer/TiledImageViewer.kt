package com.shevikina.panzoomdemo.demo.imageviewer

import android.graphics.Bitmap
import android.graphics.Rect
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shevikina.panzoomdemo.models.states.PanZoomState

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

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        // snapshotFlow считывает panZoom.scale/offset внутри корутины, а не в Composition-фазе.
        // При жесте LaunchedEffect НЕ рестартуется и рекомпозиция НЕ происходит.
        // Прежний вариант LaunchedEffect(panZoom.scale, panZoom.offset, ...) вызывал
        // рекомпозицию ~60 раз/сек на каждое движение пальца.
        LaunchedEffect(state, checkedCulling, checkedLod) {
            snapshotFlow { state.panZoom.scale to state.panZoom.offset }
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
        LaunchedEffect(state) {
            while (true) {
                withFrameMillis { state.flushPendingTiles() }
            }
        }

        TiledImageViewerContent(
            activeTiles = state.activeTiles,
            panZoom = state.panZoom,
            useGraphicsLayer = useGraphicsLayer,
        )
    }
}

@Composable
private fun TiledImageViewerContent(
    activeTiles: Map<Rect, Bitmap>,
    panZoom: PanZoomState,
    useGraphicsLayer: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

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
            activeTiles.forEach { (rect, bmp) ->
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
            activeTiles.forEach { (rect, bmp) ->
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
    }
}
