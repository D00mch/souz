package ru.gigadesk.ui.main.usecases

import ru.gigadesk.ui.main.MainEffect
import ru.gigadesk.ui.main.MainState

sealed interface MainUseCaseOutput {
    data class State(val reduce: MainState.() -> MainState) : MainUseCaseOutput
    data class Effect(val effect: MainEffect) : MainUseCaseOutput
}
