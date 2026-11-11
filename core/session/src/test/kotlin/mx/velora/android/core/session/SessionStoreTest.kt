package mx.velora.android.core.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTest {

    private val dir: File = Files.createTempDirectory("sesion").toFile().apply { deleteOnExit() }
    private val file = File(dir, "sesion.preferences_pb")

    /** Sustituye al Keystore (no existe fuera del teléfono): invierte los bytes y puede "perder la llave". */
    private class FakeCipher : SecretCipher {
        var lost = false

        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(data: ByteArray): ByteArray {
            check(!lost) { "llave perdida" }
            return encrypt(data)
        }
    }

    private val cipher = FakeCipher()

    private fun TestScope.store() =
        DataStoreSessionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, cipher)

    private val session = StoredSession("https://velora.example", "propietario@example.com", "renovacion-secreta")

    @Test
    fun savesTheSessionEncryptedAndReadsItBack() = runTest {
        val store = store()

        store.write(session)

        assertEquals(session, store.read())
        assertEquals("https://velora.example", store.lastServerUrl())
        val onDisk = file.readBytes().decodeToString()
        assertFalse(onDisk.contains("renovacion-secreta"))
        assertFalse(onDisk.contains("propietario@example.com"))
    }

    @Test
    fun clearingKeepsTheLastServer() = runTest {
        val store = store()
        store.write(session)

        store.clear()

        assertNull(store.read())
        assertEquals("https://velora.example", store.lastServerUrl())
    }

    @Test
    fun aLostKeyMeansSigningInAgain() = runTest {
        val store = store()
        store.write(session)
        cipher.lost = true

        assertNull(store.read())
    }
}
