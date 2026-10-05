package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TiledImageViewerState
import com.shevikina.panzoomdemo.presentation.di.rememberPresentationEntryPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Создаёт состояние вьюера под [source].
 *
 * Открытие декодеров и декод подложки идут на io, поэтому создание обёрнуто в NonCancellable.
 * Отмена на возврате из io выбросила бы уже построенное состояние вместе с корутиной,
 * и закрыть его стало бы некому, потому что ссылки на него нет ни у кого.
 *
 * После записи в value за закрытие отвечает awaitDispose, так что дальше отмена безопасна.
 */
@Composable
internal fun rememberTiledImageViewerState(source: ImageSource): TiledImageViewerState? {
    val entryPoint = rememberPresentationEntryPoint()
    val factory = remember(entryPoint) { entryPoint.tiledImageViewerStateFactory() }

    val state by produceState<TiledImageViewerState?>(initialValue = null, key1 = source) {
        value = null
        val created = withContext(NonCancellable) { factory.create(source) }
        value = created
        awaitDispose { created.dispose() }
    }
    return state
}
