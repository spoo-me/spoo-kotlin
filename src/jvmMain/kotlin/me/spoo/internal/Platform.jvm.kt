package me.spoo.internal

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.security.MessageDigest
import java.security.SecureRandom

internal actual fun defaultEngine(): HttpClientEngine = OkHttp.create()

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray =
    ByteArray(count).also(secureRandom::nextBytes)

internal actual fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
