package com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.domain.usecase.IClampPanOffsetUseCase
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.gesture.PanZoomState
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.toImageBitmap
import kotlinx.coroutines.channels.Channel

/**
 * Состояние наивного вьюера.
 *
 * Сводит декодированную картинку, жест и отказ отрисовки.
 * Живёт столько же, сколько композиция,
 * потому что битмап здесь весит всё изображение целиком и переживать смену конфигурации ему нельзя.
 *
 * Все мутации строго с main-потока.
 */
internal class NaiveImageViewerState(
    private val decodedImage: DecodedImage,
    clampPanOffset: IClampPanOffsetUseCase,
) {

    val panZoom = PanZoomState(clampPanOffset)

    /**
     * Обёртка над битмапом для отрисовки.
     *
     * Создаётся один раз, чтобы [NaiveImageViewerContent] не получал новый объект на каждой
     * рекомпозиции и не запускал этим следующую.
     *
     * Лениво, потому что обёртка требует Android, а состояние собирается ещё и в тесте.
     * Без синхронизации, потому что читает её только отрисовка с main.
     */
    val imageBitmap: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) { decodedImage.toImageBitmap() }

    /**
     * Отказы отрисовки, пришедшие из фазы отрисовки.
     *
     * CONFLATED, потому что платформа отклоняет тяжёлый битмап на каждом кадре,
     * а показать нужно одно сообщение.
     */
    private val drawFailures = Channel<Throwable>(Channel.CONFLATED)

    /**
     * Признак того, что платформа отклонила отрисовку.
     *
     * Композабл читает его и выходит раньше, чтобы не просить об отрисовке снова.
     */
    var isDrawRejected by mutableStateOf(false)
        private set

    /**
     * Принимает отказ отрисовки.
     *
     * Вызывается из [DrawScope], поэтому только кладёт причину в канал. Менять состояние
     * в фазе отрисовки нельзя, этим занимается [awaitDrawFailure] уже на main.
     */
    fun reportDrawFailure(cause: Throwable) {
        drawFailures.trySend(cause)
    }

    /**
     * Ждёт отказ отрисовки и поднимает [isDrawRejected].
     *
     * Флаг поднимается здесь, а не в [reportDrawFailure], потому что вызывающий обязан
     * быть на main.
     *
     * @return причина отказа.
     */
    suspend fun awaitDrawFailure(): Throwable {
        val failure = drawFailures.receive()
        isDrawRejected = true
        return failure
    }

    /**
     * Пересчитывает границы клампа под новый размер viewport.
     *
     * Границы считаются по вписанному размеру,
     * потому что картинка вписывается в экран внутри отрисовки.
     * По исходному размеру её удалось бы утащить за край экрана.
     */
    fun onViewportChanged(
        width: Float,
        height: Float,
    ) {
        val fit = fitScale(
            viewportW = width,
            viewportH = height,
            imageW = decodedImage.width,
            imageH = decodedImage.height,
        )
        panZoom.setBounds(
            viewportW = width,
            viewportH = height,
            imageW = decodedImage.width * fit,
            imageH = decodedImage.height * fit,
        )
    }
}
