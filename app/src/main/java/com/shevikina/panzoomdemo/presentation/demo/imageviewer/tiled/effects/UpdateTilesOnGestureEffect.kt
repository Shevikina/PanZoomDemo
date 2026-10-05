package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TiledImageViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate

/**
 * Пересчитывает набор тайлов, пока идёт жест.
 *
 * - [snapshotFlow] читает состояние внутри корутины, а не в Composition,
 * поэтому жест не вызывает рекомпозиций.
 * - [conflate] пропускает промежуточные значения,
 * так что пересчёт идёт примерно раз в кадр, а не на каждое событие.
 *
 * Размеры экрана стоят в ключах, чтобы после split-screen или adjustResize корутина
 * не продолжала считать viewport по старым размерам.
 */
@Composable
internal fun UpdateTilesOnGestureEffect(
    state: TiledImageViewerState,
    tileScope: CoroutineScope,
    screenW: Float,
    screenH: Float,
    checkedCulling: Boolean,
    checkedLod: Boolean,
) {
    LaunchedEffect(state, checkedCulling, checkedLod, screenW, screenH) {
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
}
