package com.shevikina.panzoomdemo.presentation.demo.elements.controlpanel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.domain.model.DemoToggle
import com.shevikina.panzoomdemo.domain.model.ViewerMode
import com.shevikina.panzoomdemo.presentation.demo.elements.displayNameRes
import com.shevikina.panzoomdemo.presentation.demo.model.DemoToggleItemUi

/** Панель управления с переключателями оптимизаций; открывается снизу через BottomSheetScaffold. */
@Composable
fun DemoControlPanel(
    selectedMode: ViewerMode,
    onModeChange: (ViewerMode) -> Unit,
    toggles: List<DemoToggleItemUi>,
    onToggleChange: (DemoToggle, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeTitles = toggles
        .filter { it.checked }
        .map { stringResource(it.titleRes) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(
                R.string.demo_panel_peek,
                stringResource(selectedMode.displayNameRes),
                activeOptionsSummary(activeTitles, stringResource(R.string.demo_panel_peek_none)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .align(Alignment.CenterHorizontally),
        )
        ViewerModeSelector(
            selectedMode = selectedMode,
            onModeChange = onModeChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        toggles.forEach { item ->
            DemoToggleItem(
                label = stringResource(item.titleRes),
                description = stringResource(item.subtitleRes),
                enabled = item.enabled,
                checked = item.checked,
                onCheckedChange = { checked -> onToggleChange(item.toggle, checked) },
            )
        }
    }
}
