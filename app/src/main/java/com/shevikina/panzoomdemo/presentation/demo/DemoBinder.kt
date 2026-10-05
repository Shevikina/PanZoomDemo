package com.shevikina.panzoomdemo.presentation.demo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.shevikina.panzoomdemo.core.mvi.Middleware
import com.shevikina.panzoomdemo.core.mvi.MviStatefulBinder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Биндер основного экрана.
 *
 * Хранит состояние тумблеров, режима просмотра и выбранного изображения.
 *
 * Выбор пользователя переживает смерть процесса.
 * Начальное состояние читается из [handle], а каждое новое состояние туда же и пишется.
 */
@HiltViewModel
class DemoBinder @Inject constructor(
    override val reducer: DemoReducer,
    middlewareFactory: DemoMiddleware.Factory,
    private val handle: SavedStateHandle,
) : MviStatefulBinder<DemoState, DemoEvent>(DemoStateSaver.restore(handle)) {

    override val middleware: Middleware<DemoState, DemoEvent> =
        middlewareFactory.create(viewModelScope)

    init {
        notifyCreated()
        // Пишем всё состояние целиком (put в Bundle дешевле, чем сравнение поле)
        viewModelScope.launch {
            state.collect { DemoStateSaver.save(handle, it) }
        }
    }
}
