package ru.souz.ui.main.usecases

import ru.souz.ui.main.MainEffect
import ru.souz.ui.main.MainState

sealed interface MainUseCaseOutput {
    data class State(val reduce: MainState.() -> MainState) : MainUseCaseOutput
    data class Effect(val effect: MainEffect) : MainUseCaseOutput
}
