package com.shevikina.panzoomdemo.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Реализация [ICoroutineDispatchers] поверх глобальных диспетчеров.
 */
class CoroutineDispatchers @Inject constructor() : ICoroutineDispatchers {
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}
