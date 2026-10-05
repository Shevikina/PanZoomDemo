package com.shevikina.panzoomdemo.presentation.demo.elements

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme

/** Сегментированная кнопка выбора тестового изображения (Small / Medium / Large). */
@Composable
fun TestImageAssetSelector(
    selectedAsset: TestImageAsset,
    onAssetChange: (TestImageAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        TestImageAsset.entries.forEachIndexed { index, asset ->
            SegmentedButton(
                selected = selectedAsset == asset,
                onClick = { if (selectedAsset != asset) onAssetChange(asset) },
                shape = SegmentedButtonDefaults.itemShape(index, TestImageAsset.entries.size),
                colors = SegmentedButtonDefaults.colors()
                    .copy(inactiveContainerColor = Color.Black.copy(alpha = 0.6f)),
                label = { Text(stringResource(asset.displayNameRes)) },
            )
        }
    }
}

@Preview
@Composable
private fun TestImageAssetSelectorPreview() {
    PanZoomDemoTheme {
        TestImageAssetSelector(
            selectedAsset = TestImageAsset.MEDIUM,
            onAssetChange = {},
        )
    }
}
