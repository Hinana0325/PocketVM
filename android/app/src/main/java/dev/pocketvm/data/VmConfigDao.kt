package dev.pocketvm.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * 一次性迁移的状态标记（与配置同库、同事务写，见 [VmConfigDao.importLegacy]）。
 *
 * key 只有 `legacy_import_done` 一个，value 恒为 "1"。它存在 Room 里而不是
 * DataStore 或标记文件，原因：导入（写多行配置）与标记必须在同一个事务里，
 * 否则「配置写进去了、标记没写上」会在下次启动时把已删的虚拟机从旧文件里
 * 复活；反过来「标记写了、配置没写全」会丢配置。
 */
@Entity(tableName = "meta")
data class MetaEntry(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface VmConfigDao {

    @Query("SELECT * FROM vm_configs ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<VmConfigEntity>

    @Query("SELECT * FROM vm_configs WHERE id = :id")
    suspend fun getById(id: String): VmConfigEntity?

    @Upsert
    suspend fun upsert(entity: VmConfigEntity)

    @Upsert
    suspend fun upsertAll(entities: List<VmConfigEntity>)

    @Query("DELETE FROM vm_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(entry: MetaEntry)

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun getMeta(key: String): String?

    /**
     * 一次性 JSON → Room 导入：配置行 + 完成标记在同一个事务里。
     *
     * 用 upsert 而不用 insert：导入是幂等的——即使标记因故丢了、导入重跑一次，
     * 同 id 的行也只是被相同内容覆盖，不会翻倍。
     */
    @Transaction
    suspend fun importLegacy(entities: List<VmConfigEntity>) {
        upsertAll(entities)
        putMeta(MetaEntry(KEY_LEGACY_IMPORT_DONE, "1"))
    }

    companion object {
        const val KEY_LEGACY_IMPORT_DONE = "legacy_import_done"
    }
}
