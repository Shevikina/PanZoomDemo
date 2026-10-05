package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.LodDebounceState

/**
 * Дебаунс смены уровня детализации: новый `inSampleSize` применяется только после
 * `SampleSize.STABLE_INVOCATIONS` подряд идущих запросов одного значения.
 * Защищает от пролёта через несколько LOD за один жест.
 */
interface IDebounceLodUseCase {

    operator fun invoke(
        state: LodDebounceState,
        desiredSampleSize: Int,
    ): LodDebounceState
}
