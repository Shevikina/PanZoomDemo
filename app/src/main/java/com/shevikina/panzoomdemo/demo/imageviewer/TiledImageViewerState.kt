package com.shevikina.panzoomdemo.demo.imageviewer

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.shevikina.panzoomdemo.models.states.PanZoomState
import com.shevikina.panzoomdemo.tiling.TilePool
import com.shevikina.panzoomdemo.tiling.TiledImage
import com.shevikina.panzoomdemo.tiling.utils.screenRectToImageRect
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "PanZoomDemo"

private data class TileEntry(val bitmap: Bitmap, val sampleSize: Int)

class TiledImageViewerState(
    private val tiledImage: TiledImage,
    private val pool: TilePool,
) {
    val panZoom = PanZoomState()

    // Внешний map содержит только Bitmap для отрисовки
    val activeTiles = mutableStateMapOf<Rect, Bitmap>()

    // Внутренний map хранит дополнительно sampleSize, с которым декодирован тайл
    private val activeTileEntries = ConcurrentHashMap<Rect, TileEntry>()

    // Per-tile loading jobs
    private val loadingJobs = ConcurrentHashMap<Rect, Job>()

    // Тайлы, декодированные на IO-потоке, ещё не внесённые в State.
    // IO-потоки только пишут сюда; main thread читает и сбрасывает в activeTiles.
    // ConcurrentHashMap — без State-нотификаций, не вызывает рекомпозицию.
    private val pendingEntries = ConcurrentHashMap<Rect, TileEntry>()

    private var lastSampleSize = 1

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
        // Сбрасываем готовые тайлы до вычисления needed/toLoad, чтобы они попали в activeTiles
        // и не были ошибочно добавлены в toLoad повторно.
        flushPendingTiles()

        val viewportInImage = if (checkedCulling) {
            screenRectToImageRect(screenW, screenH, panZoom.offset, panZoom.scale)
        } else {
            RectF(0f, 0f, tiledImage.fullWidth.toFloat(), tiledImage.fullHeight.toFloat())
        }

        val newSampleSize = if (checkedLod) tiledImage.sampleSizeForScale(panZoom.scale) else 1

        val sampleSizeChanged = newSampleSize != lastSampleSize
        if (sampleSizeChanged) {
            lastSampleSize = newSampleSize
            loadingJobs.values.forEach { it.cancel() }
            loadingJobs.clear()
            // Тайлы в pendingEntries декодированы со старым inSampleSize — выбрасываем
            pendingEntries.values.forEach { it.bitmap.recycle() }
            pendingEntries.clear()
        }

        val needed = tiledImage.visibleTileRects(viewportInImage).toSet()

        val staleByScale = if (sampleSizeChanged) {
            activeTileEntries.entries
                .filter { it.value.sampleSize != newSampleSize }
                .map { it.key }
                .toSet()
        } else {
            emptySet()
        }
        val toUnload = (activeTiles.keys - needed) + staleByScale
        // Исключаем тайлы уже в pendingEntries: они будут перенесены в activeTiles при flush
        val toLoad = needed - activeTiles.keys - loadingJobs.keys - pendingEntries.keys

        if (toLoad.isNotEmpty() || toUnload.isNotEmpty()) {
            Log.d(
                TAG,
                "LOD tiles update: scale=%.2f inSampleSize=$newSampleSize lodEnabled=$checkedLod needed=${needed.size} +${toLoad.size} -${toUnload.size}".format(
                    panZoom.scale
                )
            )
        }

        toUnload.forEach { key ->
            loadingJobs.remove(key)?.cancel()
            pendingEntries.remove(key)?.bitmap?.recycle()
            activeTileEntries.remove(key)
            activeTiles.remove(key)?.recycle()
        }

        val scaleSnapshot = if (checkedLod) panZoom.scale else 1f

        toLoad.forEach { rect ->
            loadingJobs[rect] = scope.launch(Dispatchers.IO) {
                val bmp = tiledImage.decodeTile(
                    rect = rect,
                    scale = scaleSnapshot,
                ) ?: return@launch
                val sampleSize = tiledImage.sampleSizeForScale(scaleSnapshot)
                // Не пишем в activeTiles (State) с IO-потока — кладём в pendingEntries.
                // flushPendingTiles() перенесёт все готовые тайлы за один проход на main thread,
                // что даёт максимум одну рекомпозицию на кадр вместо одной на тайл.
                pendingEntries[rect] = TileEntry(bmp, sampleSize)
                loadingJobs.remove(rect)
            }
        }
    }

    /**
     * Переносит все готовые тайлы из [pendingEntries] в [activeTiles] за одно обновление State.
     *
     * Вызывается с main thread: из [updateTiles] (на каждое изменение жеста)
     * и из frame-sync цикла в composable (когда жест завершён, но тайлы ещё грузятся).
     * Итого: максимум одна рекомпозиция на Choreographer-кадр.
     */
    fun flushPendingTiles() {
        if (pendingEntries.isEmpty()) return
        val iterator = pendingEntries.entries.iterator()
        while (iterator.hasNext()) {
            val (rect, entry) = iterator.next()
            iterator.remove()
            activeTileEntries[rect] = entry
            activeTiles[rect] = entry.bitmap
        }
    }

    fun dispose() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        pendingEntries.values.forEach { it.bitmap.recycle() }
        pendingEntries.clear()
        activeTileEntries.clear()
        activeTiles.values.forEach { it.recycle() }
        activeTiles.clear()
        tiledImage.close()
        pool.clear()
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
