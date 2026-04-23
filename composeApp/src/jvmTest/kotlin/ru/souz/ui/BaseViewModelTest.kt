@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `event collector stays alive after handler exception`() = runTest(dispatcher) {
        val viewModel = TestViewModel()
        advanceUntilIdle()

        viewModel.send(TestEvent.Crash)
        viewModel.send(TestEvent.Increment)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.counter)
    }

    @Test
    fun `side-effect collector stays alive after handler exception`() = runTest(dispatcher) {
        val viewModel = TestViewModel()
        advanceUntilIdle()

        viewModel.send(TestEvent.EmitCrashingEffect)
        viewModel.send(TestEvent.EmitSuccessEffect)
        advanceUntilIdle()

        assertEquals(1, viewModel.handledSideEffects)
    }

    private data class TestState(
        val counter: Int = 0,
    ) : VMState

    private sealed interface TestEvent : VMEvent {
        data object Crash : TestEvent
        data object Increment : TestEvent
        data object EmitCrashingEffect : TestEvent
        data object EmitSuccessEffect : TestEvent
    }

    private sealed interface TestEffect : VMSideEffect {
        data object Crash : TestEffect
        data object Success : TestEffect
    }

    private class TestViewModel : BaseViewModel<TestState, TestEvent, TestEffect>() {
        var handledSideEffects: Int = 0
            private set

        override fun initialState(): TestState = TestState()

        override suspend fun handleEvent(event: TestEvent) {
            when (event) {
                TestEvent.Crash -> error("event crash")
                TestEvent.Increment -> setState { copy(counter = counter + 1) }
                TestEvent.EmitCrashingEffect -> send(TestEffect.Crash)
                TestEvent.EmitSuccessEffect -> send(TestEffect.Success)
            }
        }

        override suspend fun handleSideEffect(effect: TestEffect) {
            when (effect) {
                TestEffect.Crash -> error("effect crash")
                TestEffect.Success -> handledSideEffects++
            }
        }
    }
}
