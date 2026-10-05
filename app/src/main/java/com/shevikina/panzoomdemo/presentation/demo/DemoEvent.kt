package com.shevikina.panzoomdemo.presentation.demo

import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode

/** События экрана демо. */
sealed interface DemoEvent {

    /** Пришли из UI. */
    sealed interface Input : DemoEvent {

        data class ViewerModeChanged(
            val mode: ViewerMode,
        ) : Input

        data class AssetChanged(
            val asset: TestImageAsset,
        ) : Input

        data class ToggleChanged(
            val toggle: DemoToggle,
            val checked: Boolean,
        ) : Input

        data object OverlayTapped : Input

        /** Снек с ошибкой дочитан: закрыт крестиком или ушёл сам. */
        data object ErrorDismissed : Input
    }

    /** Результаты побочных эффектов. */
    sealed interface Internal : DemoEvent {

        data object ImagePrepareStarted : Internal

        data class ImageSourcePrepared(
            val source: ImageSource,
        ) : Internal

        /** Ассет нужен редьюсеру: пока готовился источник, пользователь мог выбрать другой. */
        data class ImagePrepareFailed(
            val asset: TestImageAsset,
            val cause: Throwable,
        ) : Internal

        /** Изображение не удалось декодировать; в наивном режиме это штатный исход демо. */
        data class ImageLoadFailed(
            val error: ImageLoadError,
        ) : Internal
    }
}
