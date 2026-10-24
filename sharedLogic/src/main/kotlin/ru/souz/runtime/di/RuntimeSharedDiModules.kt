package ru.souz.runtime.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.skills.SkillClassifier
import ru.souz.frida.FridaClient
import ru.souz.jev.JevClient
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.runtime.FridaClassifier
import ru.souz.llms.runtime.JevClassifier

/** DI tag of the FRIDA classifier that answers when Jev fails; bound only when `FRIDA_URL` is set. */
const val FRIDA_FALLBACK_TAG = "fridaFallback"

/** Process-owned remote transports that are safe for backend and interactive hosts. */
fun runtimeProviderHttpDiModule(): DI.Module = DI.Module("runtimeProviderHttp") {
    bindSingleton { ProviderHttpClients() }
    bindSingleton { JevClient(instance<ProviderHttpClients>().jev) }
    bindSingleton<SkillClassifier> {
        SkillClassifier { request, descriptions ->
            JevClassifier(instance<JevClient>(), System.getenv("JEV_THRESHOLD")?.trim()?.toDouble() ?: 0.5)
                .selectSkills(request, descriptions)
        }
    }
    if (!System.getenv("FRIDA_URL").isNullOrBlank()) {
        // FRIDA shares the classifiers' OkHttp transport, which is named after Jev.
        bindSingleton { FridaClassifier(FridaClient(instance<ProviderHttpClients>().jev)) }
        bindSingleton<SkillClassifier>(tag = FRIDA_FALLBACK_TAG) { instance<FridaClassifier>() }
    }
    bindSingleton {
        CodexOAuthService(
            settingsProvider = instance(),
            client = instance<ProviderHttpClients>().standard,
        )
    }
}
