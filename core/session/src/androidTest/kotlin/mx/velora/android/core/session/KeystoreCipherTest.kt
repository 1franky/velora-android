package mx.velora.android.core.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Con el Keystore real del dispositivo. */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {

    private val alias = "velora-sesion-prueba"

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun encryptsWithAFreshIvAndDecrypts() {
        val cipher = KeystoreCipher(alias)
        val secret = "renovacion-secreta".encodeToByteArray()

        val first = cipher.encrypt(secret)
        val second = cipher.encrypt(secret)

        assertFalse(first.contentEquals(second))
        assertFalse(first.decodeToString(throwOnInvalidSequence = false).contains("renovacion"))
        // Otra instancia encuentra la misma llave: así se lee la sesión al volver a abrir la app.
        assertArrayEquals(secret, KeystoreCipher(alias).decrypt(first))
        assertArrayEquals(secret, cipher.decrypt(second))
    }

    @Test
    fun rejectsTamperedData() {
        val cipher = KeystoreCipher(alias)
        val data = cipher.encrypt("renovacion-secreta".encodeToByteArray())
        data[data.size - 1] = (data[data.size - 1].toInt() xor 1).toByte()

        assertThrows(Exception::class.java) { cipher.decrypt(data) }
    }
}
