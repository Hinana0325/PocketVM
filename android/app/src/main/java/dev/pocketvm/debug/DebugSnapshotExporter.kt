package dev.pocketvm.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 调试快照导出（docs/debug-api.md §7）：与 `GET /api/v1/snapshot` 用同一份模型，
 * 写到 cacheDir/debug-api/ 走 FileProvider 分享 —— 不落外部存储公开目录。
 * 内容不含 token/密钥/VNC 密码；日志只给尾巴。
 */
class DebugSnapshotExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: DebugServerManager,
) {

    suspend fun export(): Result<File> = runCatching {
        val snapshot = manager.snapshot()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "debug-api").apply { mkdirs() }
        val out = File(dir, "debug-snapshot-$stamp.json")
        out.writeText(manager.toJson(snapshot))
        out
    }
}
