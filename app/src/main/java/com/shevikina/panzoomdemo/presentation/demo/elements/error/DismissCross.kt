package com.shevikina.panzoomdemo.presentation.demo.elements.error

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.R

private val CROSS_SIZE = 18.dp
private val CROSS_STROKE = 2.dp
private const val CROSS_INSET_FRACTION = 0.1f

/**
 * Крестик закрытия снека.
 */
@Composable
internal fun DismissCross(onClick: () -> Unit) {
    val description = stringResource(R.string.demo_error_dismiss)

    IconButton(onClick = onClick) {
        Canvas(
            modifier = Modifier
                .size(CROSS_SIZE)
                .semantics { contentDescription = description },
        ) {
            val inset = size.minDimension * CROSS_INSET_FRACTION
            val stroke = Stroke(width = CROSS_STROKE.toPx(), cap = StrokeCap.Round)

            drawLine(
                color = Color.White,
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset),
                strokeWidth = stroke.width,
                cap = stroke.cap,
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width - inset, inset),
                end = Offset(inset, size.height - inset),
                strokeWidth = stroke.width,
                cap = stroke.cap,
            )
        }
    }
}
