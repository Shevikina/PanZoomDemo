package com.shevikina.panzoomdemo.presentation.demo

import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.core.mvi.Middleware
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.domain.usecase.IPrepareImageSourceUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Готовит источник изображения при старте экрана и при смене ассета.
 *
 * Отменяет прежнюю подготовку, чтобы быстрые переключения не копились в очередь.
 */
class DemoMiddleware @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val prepareImageSource: IPrepareImageSourceUseCase,
) : Middleware<DemoState, DemoEvent> {

    private var prepareJob: Job? = null

    override fun onCreate(
        state: DemoState,
        emit: (DemoEvent) -> Unit,
    ) {
        prepare(state.selectedAsset, emit)
    }

    override fun process(
        event: DemoEvent,
        state: DemoState,
        emit: (DemoEvent) -> Unit,
    ) {
        when (event) {
            is DemoEvent.Input.AssetChanged -> prepare(event.asset, emit)
            is DemoEvent.Internal.ImageLoadFailed -> {
                // Редьюсер уже выбрал Small и сбросил источник после отказа Naive.
                if (state.viewerMode == ViewerMode.NAIVE &&
                    state.selectedAsset == TestImageAsset.SMALL && state.imageSource.isLoading
                ) {
                    prepare(TestImageAsset.SMALL, emit)
                }
            }
            else -> Unit
        }
    }

    /**
     * Запускает подготовку ассета и отдаёт результат событиями.
     *
     * Прежняя подготовка сначала отменяется, чтобы быстрые переключения не запускали
     * копирование нескольких ассетов разом.
     */
    private fun prepare(
        asset: TestImageAsset,
        emit: (DemoEvent) -> Unit,
    ) {
        prepareJob?.cancel()
        emit(DemoEvent.Internal.ImagePrepareStarted)
        prepareJob = scope.launch {
            try {
                val source = prepareImageSource(asset)
                emit(DemoEvent.Internal.ImageSourcePrepared(source))
            } catch (e: CancellationException) {
                // Обязательно первым, чтобы пропустить CancellationException дальше
                // (наследует IllegalStateException, но не сообщает о некорректной загрузке битмапа)
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                DemoLog.error("Не удалось подготовить $asset: ${e.message}")
                emit(DemoEvent.Internal.ImagePrepareFailed(asset, e))
            }
        }
    }

    @AssistedFactory
    interface Factory {

        fun create(scope: CoroutineScope): DemoMiddleware
    }
}
