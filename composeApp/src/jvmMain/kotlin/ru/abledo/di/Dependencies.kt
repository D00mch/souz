package ru.abledo.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.abledo.db.ConfigStore

val mainDiModule = DI.Module("main") {
    bindSingleton { ConfigStore }
}