package ru.abledo.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.abledo.db.ConfigStore
import ru.abledo.db.SettingsProvider
import ru.abledo.giga.GigaAuth
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.giga.GigaVoiceAPI

val mainDiModule = DI.Module("main") {
    bindSingleton { ConfigStore }
    bindSingleton { SettingsProvider(instance()) }

    bindSingleton { GigaAuth }
    bindSingleton { GigaRestChatAPI(instance(), instance()) }
    bindSingleton { GigaVoiceAPI(instance(), instance()) }
}