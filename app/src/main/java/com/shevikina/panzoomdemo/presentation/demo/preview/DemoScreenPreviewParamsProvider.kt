package com.shevikina.panzoomdemo.presentation.demo.preview

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.demo.model.DemoScreenUi
import com.shevikina.panzoomdemo.presentation.demo.model.DemoToggleItemUi
import com.shevikina.panzoomdemo.presentation.metrics.MetricsOverlayVisibility

/** Состояния главного экрана, по одному превью на каждое. */
class DemoScreenPreviewParamsProvider : PreviewParameterProvider<DemoScreenPreviewParams> {
    override val values = sequenceOf(
        DemoScreenPreviewParams(ui = previewUi()),
        DemoScreenPreviewParams(ui = previewUi(), sheetExpanded = true),
        DemoScreenPreviewParams(ui = previewUi(MetricsOverlayVisibility.HIDDEN)),
    )

    /**
     * Источник изображения в превью отсутствует, потому что рендерить тайлы там нечем.
     * Панель и оверлей видны и без него.
     */
    private fun previewUi(overlayVisibility: MetricsOverlayVisibility = MetricsOverlayVisibility.FULL) = DemoScreenUi(
        viewerMode = ViewerMode.TILED,
        selectedAsset = TestImageAsset.MEDIUM,
        toggles = listOf(
            DemoToggleItemUi(
                toggle = DemoToggle.CULLING,
                titleRes = R.string.demo_culling_title,
                subtitleRes = R.string.demo_culling_subtitle,
                enabled = true,
                checked = true,
            ),
            DemoToggleItemUi(
                toggle = DemoToggle.LOD,
                titleRes = R.string.demo_lod_title,
                subtitleRes = R.string.demo_lod_subtitle,
                enabled = true,
                checked = false,
            ),
            DemoToggleItemUi(
                toggle = DemoToggle.GRAPHICS_LAYER,
                titleRes = R.string.demo_graphics_layer_title,
                subtitleRes = R.string.demo_graphics_layer_subtitle,
                enabled = true,
                checked = false,
            ),
        ),
        imageSource = null,
        isImageLoading = false,
        error = null,
        overlayVisibility = overlayVisibility,
        // Источника нет, поэтому и вьюера нет.
        viewer = null,
    )
}
