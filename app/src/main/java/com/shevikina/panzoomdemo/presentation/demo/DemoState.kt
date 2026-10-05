package com.shevikina.panzoomdemo.presentation.demo

import com.shevikina.panzoomdemo.core.mvi.RequestUI
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.metrics.MetricsOverlayVisibility

/**
 * Состояние экрана демо.
 */
data class DemoState(
    val viewerMode: ViewerMode = ViewerMode.NAIVE,
    val selectedAsset: TestImageAsset = TestImageAsset.SMALL,
    val checkedToggles: Set<DemoToggle> = emptySet(),
    val imageSource: RequestUI<ImageSource> = RequestUI.Loading,
    val imageError: ImageLoadError? = null,
    val overlayVisibility: MetricsOverlayVisibility = MetricsOverlayVisibility.FULL,
)
