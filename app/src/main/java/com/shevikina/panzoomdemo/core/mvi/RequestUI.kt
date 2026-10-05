package com.shevikina.panzoomdemo.core.mvi

/** Состояние асинхронной операции: загрузка, успех, ошибка. */
sealed interface RequestUI<out T> {

    data object Loading : RequestUI<Nothing>

    data class Success<T>(
        val data: T,
    ) : RequestUI<T>

    data class Error(
        val cause: Throwable,
    ) : RequestUI<Nothing>

    /** Данные, если запрос завершился успехом. */
    val dataOrNull: T?
        get() = (this as? Success)?.data

    val isLoading: Boolean
        get() = this is Loading
}
