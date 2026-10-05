package com.shevikina.panzoomdemo.core.mvi

/**
 * Побочные эффекты: запросы, навигация, работа с ресурсами.
 * Состояние напрямую не меняет - вместо этого эмитит новые события через [emit].
 */
interface Middleware<S, E> {

    /** Вызывается один раз при создании биндера. */
    fun onCreate(
        state: S,
        emit: (E) -> Unit,
    ) = Unit

    /** Вызывается после того, как событие уже применено редьюсером. */
    fun process(
        event: E,
        state: S,
        emit: (E) -> Unit,
    )
}
