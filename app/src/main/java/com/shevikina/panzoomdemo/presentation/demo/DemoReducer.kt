package com.shevikina.panzoomdemo.presentation.demo

import com.shevikina.panzoomdemo.core.mvi.Reducer
import com.shevikina.panzoomdemo.core.mvi.RequestUI
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import javax.inject.Inject

/**
 * Переходы состояния основного экрана.
 */
class DemoReducer @Inject constructor() : Reducer<DemoState, DemoEvent> {

    override fun reduce(
        state: DemoState,
        event: DemoEvent,
    ): DemoState = when (event) {
        is DemoEvent.Input -> reduceInput(state, event)
        is DemoEvent.Internal -> reduceInternal(state, event)
    }

    private fun reduceInput(
        state: DemoState,
        event: DemoEvent.Input,
    ): DemoState = when (event) {
        // Пока ошибка на экране, вход в наивный режим закрыт,
        // чтобы повторные тапы по Naive не запускали декод заново.
        is DemoEvent.Input.ViewerModeChanged ->
            if (event.mode == ViewerMode.NAIVE && state.imageError != null) {
                state
            } else {
                state.copy(viewerMode = event.mode)
            }

        // Смена ассета это новая попытка показа, поэтому прошлая ошибка сбрасывается.
        is DemoEvent.Input.AssetChanged -> state.copy(
            selectedAsset = event.asset,
            imageSource = RequestUI.Loading,
            imageError = null,
        )

        DemoEvent.Input.ErrorDismissed -> state.copy(imageError = null)

        is DemoEvent.Input.ToggleChanged -> state.copy(
            checkedToggles = if (event.checked) {
                state.checkedToggles + event.toggle
            } else {
                state.checkedToggles - event.toggle
            },
        )

        DemoEvent.Input.OverlayTapped ->
            state.copy(overlayVisibility = state.overlayVisibility.next())
    }

    private fun reduceInternal(
        state: DemoState,
        event: DemoEvent.Internal,
    ): DemoState = when (event) {
        DemoEvent.Internal.ImagePrepareStarted -> state.copy(imageSource = RequestUI.Loading)

        // Пока готовился источник, пользователь мог переключить ассет,
        // поэтому устаревший результат игнорируем.
        is DemoEvent.Internal.ImageSourcePrepared ->
            if (event.source.asset == state.selectedAsset) {
                state.copy(imageSource = RequestUI.Success(event.source))
            } else {
                state
            }

        // Записываем ошибку только для выбранного сейчас ассета,
        // чтобы неудачная подготовка предыдущего ассета не закрыл вход в наивный режим.
        is DemoEvent.Internal.ImagePrepareFailed ->
            if (event.asset == state.selectedAsset) {
                state.copy(
                    imageSource = RequestUI.Error(event.cause),
                    imageError = ImageLoadError.from(event.cause),
                )
            } else {
                state
            }

        // После отказа большого изображения остаёмся в Naive и загружаем Small.
        // Если отказал сам Small, повторно его не запускаем.
        is DemoEvent.Internal.ImageLoadFailed ->
            if (state.viewerMode == ViewerMode.NAIVE && state.selectedAsset != TestImageAsset.SMALL) {
                state.copy(
                    selectedAsset = TestImageAsset.SMALL,
                    imageSource = RequestUI.Loading,
                    imageError = event.error,
                )
            } else {
                state.copy(imageError = event.error)
            }
    }
}
