package com.shevikina.panzoomdemo.presentation.demo.elements.controlpanel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.presentation.theme.PanZoomDemoTheme

/** Строка переключателя с заголовком, описанием и Switch. */
@Composable
fun DemoToggleItem(
    label: String,
    description: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                color = Color.LightGray,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            enabled = enabled,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Preview
@Composable
private fun DemoToggleItemPreview() {
    PanZoomDemoTheme {
        DemoToggleItem(
            label = "Заголовок",
            description = "Пояснение",
            enabled = true,
            checked = false,
            onCheckedChange = {},
        )
    }
}
