package com.shevikina.panzoomdemo.domain.model

/**
 * Состояние дебаунса уровня детализации.
 *
 * @param current применённый сейчас `inSampleSize`.
 * @param pending кандидат на смену, ещё не набравший [SampleSize.STABLE_INVOCATIONS] подряд.
 * @param streak сколько подряд вызовов запрашивают [pending].
 */
data class LodDebounceState(
    val current: Int = SampleSize.MIN,
    val pending: Int = SampleSize.MIN,
    val streak: Int = 0,
) {
    companion object {
        val INITIAL = LodDebounceState()
    }
}
