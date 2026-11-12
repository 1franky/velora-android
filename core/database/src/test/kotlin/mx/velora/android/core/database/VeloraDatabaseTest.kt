package mx.velora.android.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VeloraDatabaseTest {

    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())

    @After fun close() = db.close()

    @Test
    fun keepsOneSummaryPerPeriodAndReplacesIt() = runTest {
        val dao = db.dashboard()
        dao.upsert(DashboardCacheEntity("month", "{\"v\":1}", 1))
        dao.upsert(DashboardCacheEntity("month", "{\"v\":2}", 2))
        dao.upsert(DashboardCacheEntity("week", "{\"v\":3}", 3))

        assertEquals(DashboardCacheEntity("month", "{\"v\":2}", 2), dao.observe("month").first())
        assertNull(dao.observe("year").first())
    }

    @Test
    fun clearingRemovesEverything() = runTest {
        db.dashboard().upsert(DashboardCacheEntity("month", "{}", 1))

        db.clearAllTables()

        assertNull(db.dashboard().observe("month").first())
    }

    private fun ride(id: String, at: Long) =
        ActivityEntity(id, "Rodada $id", "2026-11-10T13:05:00Z", at, "2026-11-10", "ROAD", 1000.0, 60, 60, null, null, null)

    @Test
    fun aPageReplacesItsRangeAndKeepsTheRest() = runTest {
        val dao = db.activities()
        dao.upsert(listOf(ride("a", 500), ride("b", 400), ride("borrada", 300), ride("c", 200), ride("vieja", 100)))

        // La API ya no tiene "borrada": entre 200 y 500 solo quedan a, b y c (con título nuevo).
        dao.replaceRange(500, 200, listOf(ride("a", 500), ride("b", 400).copy(title = "Nuevo título"), ride("c", 200)))

        val all = dao.observeAll().first()
        assertEquals(listOf("a", "b", "c", "vieja"), all.map { it.id })
        assertEquals("Nuevo título", all[1].title)
    }
}
