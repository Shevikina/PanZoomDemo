package com.shevikina.panzoomdemo.demo.elements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.models.states.ToggleState

@Composable
fun DemoControlPanel(
    toggleStateList: List<ToggleState>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.75f),
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .navigationBarsPadding()
        ) {
            toggleStateList.forEach { state ->
                DemoToggleItem(
                    label = state.title,
                    description = state.subtitle ?: "",
                    checked = state.enable,
                    onCheckedChange = state.onChange
                )
            }
        }
    }
}
