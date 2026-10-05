package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TiledImageViewerState

/**
 * Показывает ошибку декода и отпускает удерживаемые тайлы.
 *
 * Сбой приходит с io каналом состояния, а здесь его забирают уже на main, потому что чистить
 * кеш и показывать снек можно только оттуда. Ошибка та же, что и в наивном режиме, чтобы
 * нехватка памяти не убивала процесс молча.
 */
@Composable
internal fun DecodeFailureEffect(
    state: TiledImageViewerState,
    onError: (ImageLoadError) -> Unit,
) {
    // Цикл живёт до смены состояния
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(state) {
        while (true) {
            val failure = state.awaitDecodeFailure()
            DemoLog.cold { "Декод тайла упал: $failure" }
            state.dropTilesAfterFailure()
            currentOnError(ImageLoadError.from(failure))
        }
    }
}
