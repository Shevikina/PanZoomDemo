package com.shevikina.panzoomdemo.core.mvi

/**
 * Преобразование ui-состояния в ответ на событие.
 * Реализации не имеют побочных эффектов и не обращаются к Android.
 */
interface Reducer<S, E> {

    fun reduce(
        state: S,
        event: E,
    ): S
}
