package com.shevikina.panzoomdemo.test.util

import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Все диспетчеры сведены к одному тестовому: задачи выполняются предсказуемо и на месте. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestCoroutineDispatchers(
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : ICoroutineDispatchers {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}
