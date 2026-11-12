package mx.velora.android.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Quien ya tenía la app conserva su caché de Inicio al actualizar. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), VeloraDatabase::class.java)

    @Test
    fun from1To2KeepsTheSummariesAndAddsTheHistory() {
        helper.createDatabase("migracion.db", 1).use {
            it.execSQL("INSERT INTO resumenes (period, json, fetchedAtEpochMs) VALUES ('month', '{}', 7)")
        }

        val db = helper.runMigrationsAndValidate("migracion.db", 2, true)

        db.query("SELECT fetchedAtEpochMs FROM resumenes WHERE period = 'month'").use {
            it.moveToFirst()
            assertEquals(7L, it.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM actividades").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }
}
