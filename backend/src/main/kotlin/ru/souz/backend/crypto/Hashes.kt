package ru.souz.backend.crypto

private val hexFormat = java.util.HexFormat.of()

fun String.sha256Hex(): String = this@sha256Hex.toByteArray(Charsets.UTF_8).sha256Hex()
fun ByteArray.sha256Hex(): String = hexFormat.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(this))
