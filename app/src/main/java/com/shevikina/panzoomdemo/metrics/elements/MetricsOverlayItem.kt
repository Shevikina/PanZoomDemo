package com.shevikina.panzoomdemo.metrics.elements

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MetricsOverlayItem(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            color = Color.Green,
            fontSize = 12.sp,
            modifier = Modifier.padding(4.dp),
        )
    }
}