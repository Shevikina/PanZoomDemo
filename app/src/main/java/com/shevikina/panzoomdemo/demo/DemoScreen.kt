package com.shevikina.panzoomdemo.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shevikina.panzoomdemo.demo.elements.DemoControlPanel
import com.shevikina.panzoomdemo.demo.imageviewer.NaiveImageViewer
import com.shevikina.panzoomdemo.demo.imageviewer.TiledImageViewer
import com.shevikina.panzoomdemo.metrics.MetricsOverlay
import com.shevikina.panzoomdemo.models.enums.TestImageAsset.LARGE
import com.shevikina.panzoomdemo.models.enums.TestImageAsset.MEDIUM
import com.shevikina.panzoomdemo.models.enums.TestImageAsset.SMALL
import com.shevikina.panzoomdemo.models.states.DemoScreenState
import com.shevikina.panzoomdemo.models.states.DemoToggleItemState
import com.shevikina.panzoomdemo.models.states.ToggleState
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

/**
 * Главный экран демо с тумблерами для переключения оптимизаций на сцене
 */
@Composable
fun DemoScreen(viewModel: DemoScreenViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val toggleList by viewModel.toggleItemList.collectAsState()

    DemoScreenContent(state = state, toggleList = toggleList)
}

@Composable
private fun DemoScreenContent(
    state: DemoScreenState,
    toggleList: List<DemoToggleItemState>,
) {
    val assetName = when {
        LocalInspectionMode.current || (!state.naiveToggle.checked && !state.tilingToggle.checked) -> SMALL.fileName
        state.tilingToggle.checked -> LARGE.fileName
        else -> MEDIUM.fileName
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            if (state.tilingToggle.checked) {
                TiledImageViewer(
                    assetName = assetName,
                    checkedCulling = state.cullingToggle.checked,
                    checkedLod = state.lodToggle.checked,
                    useGraphicsLayer = state.graphicsLayerToggle.checked,
                )
            } else {
                NaiveImageViewer(
                    assetName = assetName,
                )
            }
        }

        MetricsOverlay(fileName = assetName)

        DemoControlPanel(
            toggleStateList = toggleList,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun DemoScreenContentPreview() {
    PanZoomDemoTheme {
        DemoScreenContent(
            state = DemoScreenState(
                tilingToggle = ToggleState(enabled = true, checked = true)
            ),
            toggleList = emptyList()
        )
    }
}
