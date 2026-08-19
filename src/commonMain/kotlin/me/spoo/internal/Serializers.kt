package me.spoo.internal

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Wire adapter for the endpoints that speak Unix epoch seconds. ISO 8601
 * fields use [Instant]'s default serializer; this one absorbs the API's
 * mixed formats so the public types are uniformly [Instant].
 */
internal object EpochSecondsSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("me.spoo.EpochSeconds", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.epochSeconds)
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.fromEpochSeconds(decoder.decodeLong())
}

/**
 * Lenient enum decoding: a wire value this SDK version does not know maps
 * to the enum's designated unknown entry instead of throwing. Additive
 * server changes must never crash a deployed app.
 */
internal open class LenientEnumSerializer<T : Enum<T>>(
    name: String,
    private val entries: List<T>,
    private val unknown: T,
    private val wireName: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(wireName(value))
    }

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return entries.firstOrNull { wireName(it) == raw } ?: unknown
    }
}
