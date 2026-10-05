package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.LodDebounceState
import com.shevikina.panzoomdemo.domain.model.SampleSize
import javax.inject.Inject

class DebounceLodUseCase @Inject constructor() : IDebounceLodUseCase {

    override fun invoke(
        state: LodDebounceState,
        desiredSampleSize: Int,
    ): LodDebounceState = when (desiredSampleSize) {
        // Запрошено то, что уже применено: копим заново.
        state.current -> state.copy(streak = 0)

        // Тот же кандидат, что и в прошлый раз: удлиняем серию, при достаточной длине применяем.
        state.pending -> {
            val streak = state.streak + 1
            if (streak >= SampleSize.STABLE_INVOCATIONS) {
                LodDebounceState(current = desiredSampleSize, pending = desiredSampleSize, streak = 0)
            } else {
                state.copy(streak = streak)
            }
        }

        // Кандидат сменился: серия начинается заново.
        else -> state.copy(pending = desiredSampleSize, streak = 1)
    }
}
