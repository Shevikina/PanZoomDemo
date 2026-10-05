package com.shevikina.panzoomdemo.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Абстракция над [kotlinx.coroutines.Dispatchers]: позволяет подменять диспетчеры в тестах.
 */
interface ICoroutineDispatchers {

    /** Главный поток: мутации Compose-состояния, работа с UI. */
    val main: CoroutineDispatcher

    /** Блокирующий ввод-вывод: декодирование тайлов, чтение файлов, /proc. */
    val io: CoroutineDispatcher

    /** CPU-bound вычисления. */
    val default: CoroutineDispatcher
}
