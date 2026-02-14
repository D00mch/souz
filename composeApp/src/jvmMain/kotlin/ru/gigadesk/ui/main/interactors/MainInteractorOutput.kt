package ru.gigadesk.ui.main.interactors

import ru.gigadesk.ui.main.MainEffect
import ru.gigadesk.ui.main.MainState

sealed interface MainInteractorOutput {
    data class State(val reduce: MainState.() -> MainState) : MainInteractorOutput
    data class Effect(val effect: MainEffect) : MainInteractorOutput
}
