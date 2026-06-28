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
import com.shevikina.panzoomdemo.models.enums.TestImageAsset
import com.shevikina.panzoomdemo.models.states.DemoScreenState
import com.shevikina.panzoomdemo.models.states.ToggleState
import com.shevikina.panzoomdemo.ui.theme.PanZoomDemoTheme

/**
 * Главный экран демо с тумблерами для переключения оптимизаций на сцене.
 */
@Composable
fun DemoScreen(viewModel: DemoScreenViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val toggleList by viewModel.toggleList.collectAsState()

    DemoScreenContent(state = state, toggleList = toggleList)
}

@Composable
private fun DemoScreenContent(
    state: DemoScreenState,
    toggleList: List<ToggleState>,
) {
    val isPreview = LocalInspectionMode.current

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
                // TODO: добавить TiledImageViewer
            if (state.tilingToggle) {
            } else {
                NaiveImageViewer(
                    assetName = if (!isPreview && state.naiveToggle) TestImageAsset.MEDIUM.fileName else TestImageAsset.SMALL.fileName,
                )
            }
        }

        MetricsOverlay()

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
            state = DemoScreenState(tilingToggle = true),
            toggleList = emptyList()
        )
    }
}
