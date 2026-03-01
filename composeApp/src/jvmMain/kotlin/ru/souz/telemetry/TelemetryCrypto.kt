package ru.souz.telemetry

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

data class TelemetrySigningKeyPair(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val encodedPrivateKey: String,
    val encodedPublicKey: String,
)

internal fun generateTelemetryIdentity(keyAlgorithm: String): TelemetryInstallationIdentity =
    TelemetryInstallationIdentity(
        userId = UUID.randomUUID().toString(),
        deviceId = UUID.randomUUID().toString(),
        keyPair = generateSigningKeyPair(keyAlgorithm),
    )

internal fun generateSigningKeyPair(keyAlgorithm: String): TelemetrySigningKeyPair {
    val generator = KeyPairGenerator.getInstance(keyAlgorithm)
    val pair = generator.generateKeyPair()
    return TelemetrySigningKeyPair(
        privateKey = pair.private,
        publicKey = pair.public,
        encodedPrivateKey = base64(pair.private.encoded),
        encodedPublicKey = base64(pair.public.encoded),
    )
}

internal fun loadSigningKeyPair(
    keyAlgorithm: String,
    encodedPrivateKey: String,
    encodedPublicKey: String,
): TelemetrySigningKeyPair {
    val keyFactory = KeyFactory.getInstance(keyAlgorithm)
    val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(base64Decode(encodedPrivateKey)))
    val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(base64Decode(encodedPublicKey)))
    return TelemetrySigningKeyPair(
        privateKey = privateKey,
        publicKey = publicKey,
        encodedPrivateKey = encodedPrivateKey,
        encodedPublicKey = encodedPublicKey,
    )
}

internal fun signTelemetryPayload(
    keyAlgorithm: String,
    privateKey: PrivateKey,
    payload: String,
): String {
    val signature = Signature.getInstance(keyAlgorithm)
    signature.initSign(privateKey)
    signature.update(payload.toByteArray(Charsets.UTF_8))
    return base64(signature.sign())
}

internal fun sha256Base64(payload: String): String =
    base64(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)))

internal fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

internal fun base64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)
