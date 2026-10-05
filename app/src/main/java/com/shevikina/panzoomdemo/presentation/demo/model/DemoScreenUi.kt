package com.shevikina.panzoomdemo.presentation.demo.model

import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.metrics.MetricsOverlayVisibility

/**
 * UI-модель главного экрана.
 *
 * @param selectedAsset ассет, выбранный в селекторе. Виден сразу на экране.
 * @param imageSource подготовленный файл выбранного ассета, либо null, пока подготовка идёт.
 * Требует времени перед отображением.
 * @param isImageLoading показывать ли индикатор загрузки
 * @param viewer вьюер текущего режима вместе с параметрами этого режима,
 * либо null, пока источник не готов.
 * */
data class DemoScreenUi(
    val viewerMode: ViewerMode,
    val selectedAsset: TestImageAsset,
    val toggles: List<DemoToggleItemUi>,
    val imageSource: ImageSource?,
    val isImageLoading: Boolean,
    val error: ImageErrorUi?,
    val overlayVisibility: MetricsOverlayVisibility,
    val viewer: ViewerUi?,
)
