package com.shevikina.panzoomdemo.presentation.metrics.elements

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Отступ между строками оверлея. */
internal val METRICS_ITEM_SPACING = 3.dp

/** Строка с несколькими метриками с подсветкой градаций каждой метрики. */
@Composable
fun MetricsOverlayItem(
    text: AnnotatedString,
    textColor: Color = Color.Green,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            modifier = Modifier.padding(4.dp),
        )
    }
}

/** Строка с одной метрикой и одним цветом у всего текста. */
@Composable
fun MetricsOverlayItem(
    text: String,
    textColor: Color = Color.Green,
    modifier: Modifier = Modifier,
) {
    MetricsOverlayItem(AnnotatedString(text), textColor, modifier)
}
