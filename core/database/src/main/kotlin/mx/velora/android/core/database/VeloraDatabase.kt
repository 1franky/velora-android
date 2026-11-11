package mx.velora.android.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Último resumen de Inicio descargado por periodo, tal como lo entregó la API (JSON del contrato): se muestra mientras
 * llega uno nuevo o si no hay conexión.
 */
@Entity(tableName = "resumenes")
data class DashboardCacheEntity(
    /** `week`, `month`, `year` o `all`, como en la API. */
    @PrimaryKey val period: String,
    val json: String,
    val fetchedAtEpochMs: Long,
)

@Dao
interface DashboardDao {
    @Query("SELECT * FROM resumenes WHERE period = :period")
    fun observe(period: String): Flow<DashboardCacheEntity?>

    @Upsert
    suspend fun upsert(entity: DashboardCacheEntity)
}

/** Caché local. Se borra completa al cerrar sesión: en el teléfono no quedan datos de una cuenta sin sesión. */
@Database(entities = [DashboardCacheEntity::class], version = 1, exportSchema = true)
abstract class VeloraDatabase : RoomDatabase() {
    abstract fun dashboard(): DashboardDao

    companion object {
        fun create(context: Context): VeloraDatabase =
            Room.databaseBuilder(context, VeloraDatabase::class.java, "velora.db").build()

        /** En memoria, para pruebas. */
        fun inMemory(context: Context): VeloraDatabase =
            Room.inMemoryDatabaseBuilder(context, VeloraDatabase::class.java).allowMainThreadQueries().build()
    }
}
