package mx.velora.android.core.network

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Serializadores de los tipos que los modelos generados marcan como `@Contextual` (el generador no los incluye):
 * `format: uuid`, `format: date` y `format: date-time` del contrato, como texto ISO.
 */
internal object ContractSerializers {

    private class AsText<T : Any>(name: String, private val parse: (String) -> T) : KSerializer<T> {
        override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.toString())

        override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())
    }

    val module = SerializersModule {
        contextual(UUID::class, AsText("mx.velora.UUID", UUID::fromString))
        contextual(LocalDate::class, AsText("mx.velora.LocalDate", LocalDate::parse))
        contextual(OffsetDateTime::class, AsText("mx.velora.OffsetDateTime", OffsetDateTime::parse))
    }
}
