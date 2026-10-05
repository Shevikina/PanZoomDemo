package com.shevikina.panzoomdemo.presentation.demo.elements.controlpanel

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.demo.elements.displayNameRes
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme

/** Сегментированная кнопка выбора режима просмотра (Naive / Tiled). */
@Composable
fun ViewerModeSelector(
    selectedMode: ViewerMode,
    onModeChange: (ViewerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        ViewerMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onModeChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ViewerMode.entries.size),
                label = { Text(stringResource(mode.displayNameRes)) },
            )
        }
    }
}

@Preview
@Composable
private fun ViewerModeSelectorPreview() {
    PanZoomDemoTheme {
        ViewerModeSelector(
            selectedMode = ViewerMode.TILED,
            onModeChange = {},
        )
    }
}
