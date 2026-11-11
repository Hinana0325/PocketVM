package dev.pocketvm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * PocketVM 的 Room 数据库（P2 数据层），version 1。
 *
 * 只有虚拟机配置一张业务表（[VmConfigEntity]）+ 一张迁移标记表
 *（[MetaEntry]）。运行清单、诊断事件、AgentFeed 仍是文件（追加型日志，
 * 不是结构化配置，不进 Room——见 [dev.pocketvm.data.VmConfigRepository]）。
 *
 * 注意两点：
 * - `exportSchema = false`：没配 Room Gradle Plugin 的 schema 输出目录，
 *   不导出 schema JSON；将来要留 schema 历史时再开插件。
 * - 没有 `fallbackToDestructiveMigration`：version 还是 1，尚无 schema 迁移；
 *   将来改表结构必须写 `Migration`，绝不能靠清库——清库等于删掉用户全部
 *   虚拟机配置。
 */
@Database(
    entities = [VmConfigEntity::class, MetaEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class VmDatabase : RoomDatabase() {

    abstract fun vmConfigDao(): VmConfigDao

    companion object {
        fun create(context: Context): VmDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VmDatabase::class.java,
                "pocketvm.db",
            ).build()
    }
}
