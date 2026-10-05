package com.shevikina.panzoomdemo.presentation.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.demo.elements.AssetSelectorOverSheet
import com.shevikina.panzoomdemo.presentation.demo.elements.LoadingOverlay
import com.shevikina.panzoomdemo.presentation.demo.elements.controlpanel.DemoControlPanel
import com.shevikina.panzoomdemo.presentation.demo.elements.error.ImageErrorSnackbar
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.ImageViewer
import com.shevikina.panzoomdemo.presentation.demo.model.DemoScreenUi
import com.shevikina.panzoomdemo.presentation.demo.model.ViewerUi
import com.shevikina.panzoomdemo.presentation.demo.preview.DemoScreenPreviewParams
import com.shevikina.panzoomdemo.presentation.demo.preview.DemoScreenPreviewParamsProvider
import com.shevikina.panzoomdemo.presentation.metrics.MetricsOverlay
import com.shevikina.panzoomdemo.presentation.metrics.TileCountsState
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme

private val SHEET_PEEK_HEIGHT = 72.dp

/**
 * Содержимое главного экрана демо.
 *
 * Всё содержимое приходит в [ui], а действия уходят колбэками.
 * Локально живут только готовность вьюера, текст снека и счётчики тайлов,
 * потому что в биндере каждый их тик перерисовывал бы весь экран.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DemoScreenContent(
    ui: DemoScreenUi,
    onToggleChange: (DemoToggle, Boolean) -> Unit,
    onViewerModeChange: (ViewerMode) -> Unit,
    onImageAssetChange: (TestImageAsset) -> Unit,
    onOverlayTap: () -> Unit = {},
    onImageError: (ImageLoadError) -> Unit = {},
    onErrorDismissed: () -> Unit = {},
    sheetState: BottomSheetScaffoldState = rememberBottomSheetScaffoldState(),
) {
    // В превью вьюера нет, готовности ждать не от кого, поэтому флаг сразу поднят.
    val isInspection = LocalInspectionMode.current
    // Источник подготовлен, но вьюеру ещё нужно открыть декодеры и набрать первые тайлы.
    // Ключ по режиму нужен в обе стороны, потому что наивный вьюер декодирует весь JPEG,
    // а тайловый при смене режима выходит из композиции и заново открывает декодеры и overview.
    var isViewerReady by remember(ui.imageSource, ui.viewerMode) { mutableStateOf(isInspection) }

    // При ошибке лоадер гасим, потому что готовности уже не будет.
    val showLoader = (ui.isImageLoading || !isViewerReady) && ui.error == null

    // Числа для строки «tiles» оверлея.
    // Держим счётчик отдельно от состояния экрана, потому что кадровый цикл вьюера обновляет
    // его раз в секунду, а через состояние каждое обновление перерисовывало бы весь экран.
    //
    // Ключ по источнику и режиму.
    // Счётчик принадлежит конкретному вьюеру, и при смене ассета или режима нужен новый.
    val tileCounts = remember(ui.imageSource, ui.viewerMode) { TileCountsState() }
    val onTilesChanged = remember(tileCounts) {
        fun(
            held: Int,
            needed: Int,
        ) = tileCounts.update(held, needed)
    }

    Box(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = sheetState,
            sheetPeekHeight = SHEET_PEEK_HEIGHT,
            containerColor = Color.White,
            sheetContainerColor = Color.Black.copy(alpha = 0.85f),
            sheetContent = {
                DemoControlPanel(
                    selectedMode = ui.viewerMode,
                    onModeChange = onViewerModeChange,
                    toggles = ui.toggles,
                    onToggleChange = onToggleChange,
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val selectorGapPx = with(density) { 8.dp.roundToPx() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    Box(
                        contentAlignment = Alignment.TopCenter,
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding(),
                    ) {
                        ui.viewer?.let { viewer ->
                            ImageViewer(
                                viewer = viewer,
                                onReady = { isViewerReady = true },
                                onError = onImageError,
                                onTilesChanged = onTilesChanged,
                            )
                        }
                    }

                    MetricsOverlay(
                        imageSizeBytes = ui.imageSource?.sizeBytes ?: 0L,
                        tileCounts = tileCounts,
                        showTiles = ui.viewer is ViewerUi.Tiled,
                        visibility = ui.overlayVisibility,
                        onTap = onOverlayTap,
                    )
                }

                AssetSelectorOverSheet(
                    selectedAsset = ui.selectedAsset,
                    onAssetChange = onImageAssetChange,
                    sheetState = sheetState,
                    gapPx = selectorGapPx,
                )
            }
        }

        if (showLoader) {
            LoadingOverlay()
        }

        // Снек рисуем сами, последним в корневом Box. У BottomSheetScaffold он всплывает
        // над шторкой и выглядит как случайно повисшая плашка, а здесь всегда прижат к низу
        // и лежит поверх всего, включая лоадер и саму шторку.
        ImageErrorSnackbar(
            error = ui.error,
            onErrorDismissed = onErrorDismissed,
        )
    }
}

@Preview(showSystemUi = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoScreenContentPreview(
    @PreviewParameter(DemoScreenPreviewParamsProvider::class) params: DemoScreenPreviewParams,
) {
    PanZoomDemoTheme {
        DemoScreenContent(
            ui = params.ui,
            onToggleChange = { _, _ -> },
            onViewerModeChange = {},
            onImageAssetChange = {},
            sheetState = rememberBottomSheetScaffoldState(
                bottomSheetState = rememberStandardBottomSheetState(
                    initialValue = if (params.sheetExpanded) SheetValue.Expanded else SheetValue.PartiallyExpanded,
                    skipHiddenState = true,
                ),
            ),
        )
    }
}
