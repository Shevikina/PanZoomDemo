package com.shevikina.panzoomdemo.presentation.demo.elements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import com.shevikina.panzoomdemo.domain.model.TestImageAsset

/**
 * Селектор ассетов, приклеенный к верхней кромке шторки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.AssetSelectorOverSheet(
    selectedAsset: TestImageAsset,
    onAssetChange: (TestImageAsset) -> Unit,
    sheetState: BottomSheetScaffoldState,
    gapPx: Int,
) {
    var selectorH by remember { mutableIntStateOf(0) }

    //  offset{} читает requireOffset() в layout-фазе без рекомпозиции,
    //  поэтому селектор плывёт синхронно с анимацией боттомшита.
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .onSizeChanged { selectorH = it.height }
            .offset {
                val sheetY = sheetState.bottomSheetState.requireOffset()
                IntOffset(0, sheetY.toInt() - selectorH - gapPx)
            },
    ) {
        TestImageAssetSelector(
            selectedAsset = selectedAsset,
            onAssetChange = onAssetChange,
        )
    }
}
