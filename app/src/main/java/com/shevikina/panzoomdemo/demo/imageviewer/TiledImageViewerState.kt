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

class TiledImageViewerState(
    private val tiledImage: TiledImage,
    private val pool: TilePool,
) {
    val panZoom = PanZoomState()
    val activeTiles = mutableStateMapOf<Rect, Bitmap>()

    // Per-tile loading jobs — отменяем конкретный тайл, а не весь scope.
    // ConcurrentHashMap: main-поток читает/пишет из updateTiles, IO-поток удаляет после загрузки.
    private val loadingJobs = ConcurrentHashMap<Rect, Job>()

    /**
     * Пересчитывает нужные тайлы для текущего viewport и запускает их загрузку.
     * Использует [scope] привязанный к composable (rememberCoroutineScope),
     * а не к LaunchedEffect — иначе каждое событие жеста отменяет все in-flight загрузки.
     */
    fun updateTiles(
        scope: CoroutineScope,
        screenW: Float,
        screenH: Float,
        checkedCulling: Boolean,
        checkedLod: Boolean,
    ) {
        val viewportInImage = if (checkedCulling) {
            screenRectToImageRect(screenW, screenH, panZoom.offset, panZoom.scale)
        } else {
            RectF(0f, 0f, tiledImage.fullWidth.toFloat(), tiledImage.fullHeight.toFloat())
        }

        val needed = tiledImage.visibleTileRects(viewportInImage).toSet()
        val toUnload = activeTiles.keys - needed
        // Не запускаем тайлы, которые уже загружены или грузятся прямо сейчас
        val toLoad = needed - activeTiles.keys - loadingJobs.keys

        if (toLoad.isNotEmpty() || toUnload.isNotEmpty()) {
            val sampleSize = if (checkedLod) tiledImage.sampleSizeForScale(panZoom.scale) else 1
            Log.d(
                TAG,
                "LOD tiles update: scale=%.2f inSampleSize=$sampleSize lodEnabled=$checkedLod needed=${needed.size} +${toLoad.size} -${toUnload.size}".format(
                    panZoom.scale
                )
            )
        }

        // Rendered bitmaps are GPU-pinned by HWUI — pooling them as inBitmap targets
        // causes "Reusing an immutable bitmap" corruption. Release directly instead.
        toUnload.forEach { key ->
            loadingJobs.remove(key)?.cancel()
            activeTiles.remove(key)?.recycle()
        }

        // Захватываем scale ДО запуска корутины — panZoom.scale может измениться
        // к моменту выполнения IO-корутины (пользователь успел зумнуть),
        // что приводило к декодированию inSampleSize=1 вместо нужного 4 → 16× лишней памяти
        val scaleSnapshot = if (checkedLod) panZoom.scale else 1f

        toLoad.forEach { rect ->
            loadingJobs[rect] = scope.launch(Dispatchers.IO) {
                val bmp = tiledImage.decodeTile(
                    rect = rect,
                    scale = scaleSnapshot,
                ) ?: return@launch
                activeTiles[rect] = bmp
                loadingJobs.remove(rect)
            }
        }
    }

    fun dispose() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
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
