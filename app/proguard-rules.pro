# kotlinx.serialization: conservar los serializadores generados de los modelos de la API.
-keepclassmembers @kotlinx.serialization.Serializable class mx.velora.android.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
