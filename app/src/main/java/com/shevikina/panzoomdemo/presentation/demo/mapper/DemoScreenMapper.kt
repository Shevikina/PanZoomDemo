package com.shevikina.panzoomdemo.presentation.demo.mapper

import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.demo.DemoState
import com.shevikina.panzoomdemo.presentation.demo.model.DemoScreenUi
import com.shevikina.panzoomdemo.presentation.demo.model.DemoToggleItemUi
import com.shevikina.panzoomdemo.presentation.demo.model.ImageErrorUi
import com.shevikina.panzoomdemo.presentation.demo.model.ViewerUi

/**
 * Состояние биндера в UI-модель экрана.
 *
 * Тумблеры оптимизаций доступны только в тайловом режиме.
 * В наивном режиме тогглы отображаются выключенными, даже если в тайловом были включены.
 * Настоящие значения остаются в состоянии и возвращаются при переходе обратно в тайловый режим.
 */
fun DemoState.toUi(): DemoScreenUi = DemoScreenUi(
    viewerMode = viewerMode,
    selectedAsset = selectedAsset,
    toggles = DemoToggle.entries.map { toggle ->
        DemoToggleItemUi(
            toggle = toggle,
            titleRes = toggle.titleRes(),
            subtitleRes = toggle.subtitleRes(),
            enabled = viewerMode == ViewerMode.TILED,
            checked = viewerMode == ViewerMode.TILED && toggle in checkedToggles,
        )
    },
    imageSource = imageSource.dataOrNull,
    // Пока показываем ошибку, крутить лоадер бессмысленно.
    isImageLoading = imageSource.isLoading && imageError == null,
    error = imageError?.toUi(),
    overlayVisibility = overlayVisibility,
    viewer = toViewerUi(),
)

/**
 * Собирает вьюер текущего режима.
 *
 * Возвращает null, пока файл не подготовлен, потому что рисовать ещё нечего.
 * Наивному режиму хватает ассета, а тумблеры имеют смысл только в тайловом.
 */
private fun DemoState.toViewerUi(): ViewerUi? {
    val source = imageSource.dataOrNull ?: return null
    return when (viewerMode) {
        ViewerMode.NAIVE -> ViewerUi.Naive(source.asset)
        ViewerMode.TILED -> ViewerUi.Tiled(
            source = source,
            culling = DemoToggle.CULLING in checkedToggles,
            lod = DemoToggle.LOD in checkedToggles,
            useGraphicsLayer = DemoToggle.GRAPHICS_LAYER in checkedToggles,
        )
    }
}

private fun ImageLoadError.toUi(): ImageErrorUi = ImageErrorUi(type = type, message = message)

private fun DemoToggle.titleRes(): Int = when (this) {
    DemoToggle.CULLING -> R.string.demo_culling_title
    DemoToggle.LOD -> R.string.demo_lod_title
    DemoToggle.GRAPHICS_LAYER -> R.string.demo_graphics_layer_title
}

private fun DemoToggle.subtitleRes(): Int = when (this) {
    DemoToggle.CULLING -> R.string.demo_culling_subtitle
    DemoToggle.LOD -> R.string.demo_lod_subtitle
    DemoToggle.GRAPHICS_LAYER -> R.string.demo_graphics_layer_subtitle
}
