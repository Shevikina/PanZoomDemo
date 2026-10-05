package com.shevikina.panzoomdemo.presentation.metrics.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val RETURN_ZONE_SIZE = 48.dp
private const val RETURN_ZONE_ALPHA = 0.12f
private val RETURN_ZONE_PADDING = 8.dp

/**
 * Зона, по которой скрытый оверлей возвращается на экран.
 *
 * Стоит там же, где жила сама плашка.
 *
 * На внешнем Box не должно быть ни одного pointer-input модификатора.
 */
@Composable
internal fun ReturnZone(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(RETURN_ZONE_PADDING),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(RETURN_ZONE_SIZE)
                .background(Color.White.copy(alpha = RETURN_ZONE_ALPHA), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    // Без indication, потому что LocalIndication рисовал бы ripple на весь экран
                    indication = null,
                    onClick = onTap,
                ),
        )
    }
}
