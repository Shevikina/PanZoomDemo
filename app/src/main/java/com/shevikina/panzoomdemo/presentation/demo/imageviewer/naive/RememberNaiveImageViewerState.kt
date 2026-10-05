package com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.presentation.di.rememberPresentationEntryPoint
import kotlinx.coroutines.CancellationException

/**
 * Создаёт состояние наивного вьюера под [asset].
 *
 * Возвращает null, пока изображение декодируется, и после неудачного декода.
 */
@Suppress("TooGenericExceptionCaught")
@Composable
internal fun rememberNaiveImageViewerState(
    asset: TestImageAsset,
    onError: (ImageLoadError) -> Unit,
): NaiveImageViewerState? {
    val entryPoint = rememberPresentationEntryPoint()
    val decodeFullImage = remember(entryPoint) { entryPoint.decodeFullImageUseCase() }
    val clampPanOffset = remember(entryPoint) { entryPoint.clampPanOffsetUseCase() }
    // Блок [produceState] перезапускается только по смене ассета
    val currentOnError by rememberUpdatedState(onError)

    val state by produceState<NaiveImageViewerState?>(initialValue = null, key1 = asset) {
        // Ссылка на прошлый битмап отпускается до нового декода, чтобы в памяти не оказалось двух.
        value = null
        value = try {
            NaiveImageViewerState(decodeFullImage(asset), clampPanOffset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Перехватываем [OutOfMemoryError], чтобы показать его пользователю.
            DemoLog.error("Наивная загрузка $asset упала: $e")
            currentOnError(ImageLoadError.from(e))
            null
        }
    }
    return state
}
