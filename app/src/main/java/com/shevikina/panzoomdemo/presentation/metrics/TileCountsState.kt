package com.shevikina.panzoomdemo.presentation.metrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Счётчик тайлов, удерживаемых в памяти и требуемых для отрисовки.
 *
 * Числа рождаются в кадровом цикле тайлового вьюера, а показывает их строка оверлея.
 * Держатель связывает два узла дерева без общего состояния напрямую, минуя биндер экрана.
 * Так запись счётчика перерисовывает только строку оверлея, а не весь [DemoScreenContent].
 */
@Stable
class TileCountsState(
    held: Int = 0,
    needed: Int = 0,
) {

    /** Тайлы, уже готовые к отрисовке. */
    var held by mutableIntStateOf(held)
        private set

    /** Тайлы, нужные для текущего viewport. */
    var needed by mutableIntStateOf(needed)
        private set

    fun update(
        held: Int,
        needed: Int,
    ) {
        this.held = held
        this.needed = needed
    }
}
