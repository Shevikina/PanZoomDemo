package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.FrameLoopStats
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TiledImageViewerState
import kotlinx.coroutines.CoroutineScope

/**
 * Переносит готовые тайлы в активный слой, возвращает буферы и подкачивает очередь.
 *
 * Работает не чаще раза в кадр и нужен, когда жест уже закончился, а тайлы ещё догружаются.
 * Спит, пока работы нет, чтобы `withFrameMillis` не просил кадр вечно и не держал приложение
 * в непрерывном рендере.
 *
 * Раз в секунду отдаёт числа оверлею метрик. Подписать оверлей напрямую не на что, потому что
 * `activeTiles` это обычная карта, а `revision` сигнал фазы отрисовки, и чтение его
 * в композиции сломало бы то самое свойство, которое демонстрирует этот режим.
 */
@Composable
internal fun FrameLoopEffect(
    state: TiledImageViewerState,
    tileScope: CoroutineScope,
    onTilesChanged: (held: Int, needed: Int) -> Unit,
) {
    // Цикл живёт до смены состояния
    val currentOnTilesChanged by rememberUpdatedState(onTilesChanged)

    LaunchedEffect(state) {
        val stats = FrameLoopStats()
        while (true) {
            state.awaitFrameWork()
            withFrameMillis { frameMs ->
                val pendingBefore = state.pendingTilesCount
                state.flushPendingTiles()
                stats.record(
                    flushed = pendingBefore - state.pendingTilesCount,
                    recycled = state.drainRecyclable(),
                    pumped = state.pumpToLoadQueue(tileScope),
                )

                val reportDue = stats.isReportDue(frameMs)
                if (reportDue || state.isIdle) {
                    currentOnTilesChanged(state.activeTiles.size, state.neededTilesCount)
                }
                if (reportDue) {
                    logFrameLoop(state, stats)
                    stats.reset(frameMs)
                }
            }
        }
    }
}

/**
 * Пишет в лог сводку кадрового цикла за секунду.
 *
 * Молчит, когда за секунду ничего не происходило, потому что счётчик пробуждений
 * в спящем цикле ни о чём не говорит.
 */
private fun logFrameLoop(
    state: TiledImageViewerState,
    stats: FrameLoopStats,
) {
    if (stats.isIdle && state.isIdle) return
    DemoLog.hot {
        "frameLoop: wakeups=${stats.wakeups}/s | flushed=${stats.flushed}/s " +
            "recycled=${stats.recycled}/s pumped=${stats.pumped}/s " +
            "active=${state.activeTiles.size} pending=${state.pendingTilesCount} " +
            "loading=${state.loadingTilesCount} " +
            "recyclePending=${state.releasePendingCount} " +
            "toLoadQueue=${state.toLoadQueueCount}"
    }
}
