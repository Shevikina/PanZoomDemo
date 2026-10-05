package com.shevikina.panzoomdemo.presentation.demo

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import com.shevikina.panzoomdemo.presentation.demo.mapper.toUi

/**
 * Главный экран демо
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(binder: DemoBinder = hiltViewModel()) {
    val state by binder.state.collectAsState()
    val ui = remember(state) { state.toUi() }

    DemoScreenContent(
        ui = ui,
        onToggleChange = { toggle, checked ->
            binder.emit(DemoEvent.Input.ToggleChanged(toggle, checked))
        },
        onViewerModeChange = { binder.emit(DemoEvent.Input.ViewerModeChanged(it)) },
        onImageAssetChange = { binder.emit(DemoEvent.Input.AssetChanged(it)) },
        onOverlayTap = { binder.emit(DemoEvent.Input.OverlayTapped) },
        onImageError = { binder.emit(DemoEvent.Internal.ImageLoadFailed(it)) },
        onErrorDismissed = { binder.emit(DemoEvent.Input.ErrorDismissed) },
    )
}
