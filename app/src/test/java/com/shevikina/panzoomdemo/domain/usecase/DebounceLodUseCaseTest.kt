package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.LodDebounceState
import com.shevikina.panzoomdemo.domain.model.SampleSize
import io.kotest.matchers.shouldBe
import org.junit.Test

class DebounceLodUseCaseTest {

    private val useCase = DebounceLodUseCase()

    @Test
    fun `should apply new level after enough stable invocations`() {
        var state = LodDebounceState.INITIAL

        repeat(SampleSize.STABLE_INVOCATIONS) { state = useCase(state, DESIRED) }

        state.current shouldBe DESIRED
        state.streak shouldBe 0
    }

    @Test
    fun `should hold current level while streak is too short`() {
        var state = LodDebounceState.INITIAL

        repeat(SampleSize.STABLE_INVOCATIONS - 1) { state = useCase(state, DESIRED) }

        state.current shouldBe SampleSize.MIN
        state.streak shouldBe SampleSize.STABLE_INVOCATIONS - 1
    }

    @Test
    fun `should restart streak when candidate changes`() {
        var state = useCase(LodDebounceState.INITIAL, DESIRED)
        state = useCase(state, OTHER_DESIRED)

        state.current shouldBe SampleSize.MIN
        state.pending shouldBe OTHER_DESIRED
        state.streak shouldBe 1
    }

    @Test
    fun `should reset streak when desired equals current`() {
        val state = useCase(LodDebounceState(current = 2, pending = 4, streak = 2), desiredSampleSize = 2)

        state.current shouldBe 2
        state.streak shouldBe 0
    }

    private companion object {
        const val DESIRED = 2
        const val OTHER_DESIRED = 4
    }
}
