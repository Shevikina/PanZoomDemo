package com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.ImageLoadError

/**
 * Показывает отказ отрисовки и останавливает вьюер.
 *
 * Платформа отклоняет слишком тяжёлый битмап уже в фазе отрисовки,
 * и [RecordingCanvas] бросает исключение прямо из [DrawScope].
 * Менять состояние оттуда нельзя, поэтому отказ едет по цепочке
 * от канала к этому эффекту, а он поднимает флаг остановки на main.
 *
 * Ошибка та же, что и при неудачном декоде, чтобы отказ не остался незамеченным.
 */
@Composable
internal fun DrawFailureEffect(
    state: NaiveImageViewerState,
    onError: (ImageLoadError) -> Unit,
) {
    // Цикл живёт до смены состояния
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(state) {
        while (true) {
            val failure = state.awaitDrawFailure()
            DemoLog.error("Наивная отрисовка отклонена платформой: $failure")
            currentOnError(ImageLoadError.from(failure))
        }
    }
}
