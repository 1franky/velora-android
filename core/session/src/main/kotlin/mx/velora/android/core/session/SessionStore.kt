package mx.velora.android.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lo que sobrevive a cerrar la app: nunca la contraseña ni el access token (que dura 15 minutos y vive en memoria). */
@Serializable
data class StoredSession(val serverUrl: String, val email: String, val refreshToken: String)

interface SessionStore {
    suspend fun read(): StoredSession?

    suspend fun write(session: StoredSession)

    /** Borra la sesión; conserva el último servidor para no tener que escribirlo de nuevo. */
    suspend fun clear()

    suspend fun lastServerUrl(): String?
}

/** Cifra y descifra bytes. En el teléfono, con una llave del Keystore que no sale del hardware. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(data: ByteArray): ByteArray
}

/** AES-256-GCM con una llave del Android Keystore; el resultado es el IV (12 bytes) seguido del texto cifrado. */
class KeystoreCipher(private val alias: String = "velora-sesion") : SecretCipher {

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** La sesión va cifrada completa (servidor, correo y refresh token); el último servidor, en claro. */
class DataStoreSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : SessionStore {

    override suspend fun read(): StoredSession? {
        val encrypted = dataStore.data.first()[SESSION] ?: return null
        return try {
            val plain = cipher.decrypt(Base64.getDecoder().decode(encrypted)).decodeToString()
            Json.decodeFromString(StoredSession.serializer(), plain)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Llave perdida (p. ej. datos restaurados en otro teléfono) o datos dañados: hay que volver a entrar.
            null
        }
    }

    override suspend fun write(session: StoredSession) {
        val plain = Json.encodeToString(StoredSession.serializer(), session).encodeToByteArray()
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(plain))
        dataStore.edit {
            it[SESSION] = encrypted
            it[LAST_SERVER] = session.serverUrl
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(SESSION) }
    }

    override suspend fun lastServerUrl(): String? = dataStore.data.first()[LAST_SERVER]

    private companion object {
        val SESSION = stringPreferencesKey("sesion_cifrada")
        val LAST_SERVER = stringPreferencesKey("ultimo_servidor")
    }
}

/** El almacén del teléfono. Uno solo por proceso: DataStore no admite dos instancias sobre el mismo archivo. */
fun createSessionStore(context: Context): SessionStore =
    DataStoreSessionStore(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("sesion") },
        KeystoreCipher(),
    )
