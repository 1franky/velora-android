package mx.velora.android.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
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

/**
 * Una rodada del historial, con los campos de `ActivitySummary`. Solo se guarda el historial sin filtros: es lo que se
 * muestra sin conexión.
 */
@Entity(tableName = "actividades", indices = [Index(value = ["startedAtEpochMs"])])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Instante ISO-8601 con zona, como lo entrega la API. */
    val startedAt: String,
    /** El mismo instante, para ordenar y comparar. */
    val startedAtEpochMs: Long,
    /** Fecha deportiva local (yyyy-MM-dd). */
    val localStartDate: String,
    val subType: String,
    val distanceMeters: Double?,
    val movingTimeSeconds: Int,
    val elapsedTimeSeconds: Int,
    val elevationGainMeters: Double?,
    val averageSpeedMps: Double?,
    val bikeId: String?,
)

@Dao
interface ActivityDao {
    /** De la más reciente a la más antigua, como la API. */
    @Query("SELECT * FROM actividades ORDER BY startedAtEpochMs DESC, id DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    /**
     * Reemplaza lo guardado en el tramo que cubre una página de la API: lo que ya no viene en ella se borró en el
     * servidor. [newestEpochMs] y [oldestEpochMs] son los extremos del tramo, incluidos.
     */
    @Transaction
    suspend fun replaceRange(newestEpochMs: Long, oldestEpochMs: Long, page: List<ActivityEntity>) {
        deleteRangeExcept(newestEpochMs, oldestEpochMs, page.map { it.id })
        upsert(page)
    }

    @Query(
        "DELETE FROM actividades WHERE startedAtEpochMs <= :newestEpochMs AND startedAtEpochMs >= :oldestEpochMs " +
            "AND id NOT IN (:keep)",
    )
    suspend fun deleteRangeExcept(newestEpochMs: Long, oldestEpochMs: Long, keep: List<String>)

    @Upsert
    suspend fun upsert(activities: List<ActivityEntity>)
}

/** Caché local. Se borra completa al cerrar sesión: en el teléfono no quedan datos de una cuenta sin sesión. */
@Database(
    entities = [DashboardCacheEntity::class, ActivityEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class VeloraDatabase : RoomDatabase() {
    abstract fun dashboard(): DashboardDao

    abstract fun activities(): ActivityDao

    companion object {
        fun create(context: Context): VeloraDatabase =
            Room.databaseBuilder(context, VeloraDatabase::class.java, "velora.db").build()

        /** En memoria, para pruebas. */
        fun inMemory(context: Context): VeloraDatabase =
            Room.inMemoryDatabaseBuilder(context, VeloraDatabase::class.java).allowMainThreadQueries().build()
    }
}
