package com.shevikina.panzoomdemo.presentation.demo.elements.error

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shevikina.panzoomdemo.presentation.demo.model.ImageErrorUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val SNACKBAR_MARGIN = 12.dp

/**
 * Показывает ошибку загрузки снеком поверх всего экрана.
 *
 * Текст держится локально, потому что автопереключение в Tiled чистит ошибку в состоянии,
 * а сообщение должно дочитаться до конца.
 *
 * SnackbarHostState показывает сообщения строго по очереди, под мьютексом. Поэтому перед
 * новой ошибкой прежний показ отменяется, чтобы она не ждала, пока досидит старый снек.
 * Отмена заодно закрывает снек на экране.
 */
@Composable
internal fun BoxScope.ImageErrorSnackbar(
    error: ImageErrorUi?,
    onErrorDismissed: () -> Unit,
) {
    val hostState = remember { SnackbarHostState() }
    var shownError by remember { mutableStateOf<ImageErrorUi?>(null) }
    val scope = rememberCoroutineScope()
    // Показ идёт в скоупе композабла и переживает эффект, который его запустил
    val currentOnErrorDismissed by rememberUpdatedState(onErrorDismissed)

    // Читается только внутри эффекта, поэтому композицию не трогает.
    var showJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(error) {
        val shown = error ?: return@LaunchedEffect
        shownError = shown
        showJob?.cancel()
        // Запускаем показ в скоупе композабла, а не эффекта, потому что смена error
        // его бы отменила.
        showJob = scope.launch {
            hostState.showSnackbar(message = shown.type, duration = SnackbarDuration.Long)
            // Сообщение дочитано, поэтому снимаем ошибку.
            currentOnErrorDismissed()
        }
    }

    ImageErrorSnackbarHost(
        hostState = hostState,
        error = shownError,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(SNACKBAR_MARGIN),
    )
}
