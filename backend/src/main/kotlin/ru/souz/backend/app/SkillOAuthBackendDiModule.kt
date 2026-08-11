package ru.souz.backend.app

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.skilloauth.impl.AuthorizationCodeOAuthClient
import ru.souz.skilloauth.impl.AuthorizationCodeOAuthConfig
import ru.souz.skilloauth.impl.OAuthProviderCatalog
import ru.souz.skilloauth.impl.OAuthProviderClient
import ru.souz.skilloauth.impl.PostgresSkillOAuthCredentialRepository
import ru.souz.skilloauth.impl.PostgresSkillOAuthPendingStateRepository
import ru.souz.skilloauth.impl.SkillOAuthCredentialRepository
import ru.souz.skilloauth.impl.SkillOAuthGatewayImpl
import ru.souz.skilloauth.impl.SkillOAuthPendingStateRepository
import ru.souz.skilloauth.impl.SkillOAuthTokenCrypto

/**
 * Resolved from [BackendAppConfig] once, up front. `null` means skill OAuth stays fully disabled
 * for this deployment — a fresh install with nothing configured yet, not a broken one — and
 * [skillOAuthBackendModule] then binds nothing at all, so `instanceOrNull<SkillOAuthGateway>()`
 * (see `portableRuntimeToolsDiModule`) resolves to null and the OAuth tools/callback route stay
 * disabled.
 *
 * Gating on [providers] being non-empty, not just on the encryption key being present, matters:
 * a key with zero fully-configured providers (any provider missing even one of its client
 * id/secret/redirect-uri env vars is dropped by [BackendAppConfig]) is just as inert as no key at
 * all — binding a live [SkillOAuthGateway] in that state would only let the OAuth tools/callback
 * route come up promising a connection that can never succeed.
 */
class SkillOAuthBackendConfig private constructor(
    val tokenEncryptionKey: String,
    val providers: Map<String, OAuthProviderClient>,
) {
    companion object {
        fun from(appConfig: BackendAppConfig): SkillOAuthBackendConfig? {
            val tokenEncryptionKey = appConfig.skillOAuthTokenEncryptionKey ?: return null
            val providers = OAuthProviderCatalog.entries
                .mapNotNull { entry ->
                    val credentials = appConfig.skillOAuthProviderCredentials[entry.name] ?: return@mapNotNull null
                    entry.name to AuthorizationCodeOAuthClient(
                        AuthorizationCodeOAuthConfig(
                            name = entry.name,
                            authorizeEndpoint = entry.authorizeEndpoint,
                            tokenEndpoint = entry.tokenEndpoint,
                            clientId = credentials.clientId,
                            clientSecret = credentials.clientSecret,
                            redirectUri = credentials.redirectUri,
                            allowedApiHosts = entry.allowedApiHosts,
                        ),
                    )
                }
                .toMap()
            if (providers.isEmpty()) return null
            return SkillOAuthBackendConfig(tokenEncryptionKey, providers)
        }
    }
}

/**
 * Isolates every skill-OAuth DI decision behind one module, so `backendDiModule` itself doesn't
 * need to know the shape of [SkillOAuthGatewayImpl]'s dependencies, or that "disabled" means
 * "don't bind [SkillOAuthGateway] at all" rather than wiring some sentinel/no-op implementation.
 * When [config] is null this binds nothing; every binding below only exists to construct
 * [SkillOAuthGatewayImpl] itself, so there's nothing useful to expose when it isn't wired.
 */
fun skillOAuthBackendModule(config: SkillOAuthBackendConfig?): DI.Module = DI.Module("skillOAuthBackend") {
    if (config != null) {
        bindSingleton<SkillOAuthCredentialRepository> { PostgresSkillOAuthCredentialRepository(instance()) }
        bindSingleton<SkillOAuthPendingStateRepository> { PostgresSkillOAuthPendingStateRepository(instance()) }
        bindSingleton { SkillOAuthTokenCrypto(rawBase64Key = config.tokenEncryptionKey) }
        bindSingleton {
            SkillOAuthGatewayImpl(
                credentialRepository = instance(),
                pendingStateRepository = instance(),
                crypto = instance(),
                providers = config.providers,
            )
        }
        bindSingleton<SkillOAuthGateway> { instance<SkillOAuthGatewayImpl>() }
    }
}
