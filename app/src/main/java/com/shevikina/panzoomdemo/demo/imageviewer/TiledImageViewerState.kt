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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PanZoomDemo"

class TiledImageViewerState(
    private val tiledImage: TiledImage,
    private val pool: TilePool,
) {
    val panZoom = PanZoomState()
    val activeTiles = mutableStateMapOf<Rect, Bitmap>()

    /**
     * Пересчитывает нужные тайлы для текущего viewport и запускает их загрузку
     * как дочерние корутины переданного [scope] - отменяются вместе с ним.
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
        val toLoad = needed - activeTiles.keys

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
            activeTiles.remove(key)?.recycle()
        }

        toLoad.forEach { rect ->
            scope.launch(Dispatchers.IO) {
                val bmp = tiledImage.decodeTile(
                    rect = rect,
                    scale = if (checkedLod) panZoom.scale else 1f,
                ) ?: return@launch
                activeTiles[rect] = bmp
            }
        }
    }

    fun dispose() {
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
