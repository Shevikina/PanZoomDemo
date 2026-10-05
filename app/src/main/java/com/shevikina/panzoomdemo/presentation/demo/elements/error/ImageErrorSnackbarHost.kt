package com.shevikina.panzoomdemo.presentation.demo.elements.error

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.presentation.demo.model.ImageErrorUi
import com.shevikina.panzoomdemo.presentation.theme.ErrorRed

private val SNACKBAR_CORNER = 12.dp
private const val ERROR_MAX_LINES = 5

/**
 * Снек об ошибке загрузки изображения
 */
@Composable
internal fun ImageErrorSnackbarHost(
    hostState: SnackbarHostState,
    error: ImageErrorUi?,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        Snackbar(
            shape = RoundedCornerShape(SNACKBAR_CORNER),
            containerColor = ErrorRed,
            contentColor = Color.White,
            dismissActionContentColor = Color.White,
            dismissAction = { DismissCross(onClick = data::dismiss) },
        ) {
            Text(
                text = imageErrorText(stringResource(R.string.demo_error_prefix), error),
                color = Color.White,
                maxLines = ERROR_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
