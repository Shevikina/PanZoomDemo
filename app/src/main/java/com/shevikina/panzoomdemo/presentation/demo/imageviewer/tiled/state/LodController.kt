package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import com.shevikina.panzoomdemo.core.log.DemoLog
import com.shevikina.panzoomdemo.domain.model.LodDebounceState
import com.shevikina.panzoomdemo.domain.usecase.IDebounceLodUseCase
import com.shevikina.panzoomdemo.domain.usecase.IResolveSampleSizeUseCase

/**
 * Состояние текущего уровня детализации.
 * Считает желаемый `inSampleSize` по масштабу и пропускает его через дебаунс.
 */
internal class LodController(
    private val resolveSampleSize: IResolveSampleSizeUseCase,
    private val debounceLod: IDebounceLodUseCase,
) {

    private var debounceState = LodDebounceState.INITIAL

    /** Применённый сейчас `inSampleSize`. */
    val sampleSize: Int
        get() = debounceState.current

    /**
     * Пересчитывает уровень детализации под [scale].
     * @return true, если уровень сменился и кеш нужно перестроить.
     */
    fun update(
        scale: Float,
        lodEnabled: Boolean,
    ): Boolean {
        val desired = resolveSampleSize(scale, debounceState.current, lodEnabled)
        val previous = debounceState
        debounceState = debounceLod(previous, desired)
        if (debounceState.current == previous.current) {
            if (desired != previous.current && debounceState.streak > 0) {
                DemoLog.hot {
                    "LOD debounce: hold ss=${previous.current}, desired=$desired streak=${debounceState.streak}"
                }
            }
            return false
        }
        return true
    }
}
