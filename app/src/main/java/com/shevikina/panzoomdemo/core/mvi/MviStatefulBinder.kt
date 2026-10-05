package com.shevikina.panzoomdemo.core.mvi

import android.os.Looper
import androidx.lifecycle.ViewModel
import com.shevikina.panzoomdemo.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Лёгкий MVI-биндер: хранит состояние, прогоняет события через [reducer]
 * и отдаёт их в [middleware] для побочных эффектов.
 *
 * Событие сначала меняет состояние, и только потом попадает в middleware: так middleware
 * видит уже актуальное состояние и не гонится с редьюсером.
 *
 * **Контракт: [emit] вызывается только с main-потока.** Метод реентерабелен (middleware
 * эмитит новые события прямо изнутри) и не потокобезопасен: `_state.update` атомарен,
 * а чтение `_state.value` следующей строкой - уже нет. Проверяется в debug-сборке.
 */
abstract class MviStatefulBinder<S, E>(
    initialState: S,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    protected abstract val reducer: Reducer<S, E>

    protected open val middleware: Middleware<S, E>? = null

    /** Единственная точка входа для событий из UI и из middleware. */
    fun emit(event: E) {
        checkMainThread()
        _state.update { reducer.reduce(it, event) }
        middleware?.process(event, _state.value, ::emit)
    }

    private fun checkMainThread() {
        if (!BuildConfig.DEBUG) return
        // В юнит-тестах Looper'а нет
        val main = Looper.getMainLooper() ?: return
        check(Looper.myLooper() == main) {
            "emit вызван не с main-потока: ${Thread.currentThread().name}"
        }
    }

    /** Вызывать из init наследника, когда middleware уже собран. */
    protected fun notifyCreated() {
        middleware?.onCreate(_state.value, ::emit)
    }
}
