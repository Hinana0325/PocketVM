package dev.pocketvm.data

import dev.pocketvm.core.model.CdTransport
import dev.pocketvm.core.model.DisplayDevice
import dev.pocketvm.core.model.LaunchMode
import dev.pocketvm.core.model.PortForward
import dev.pocketvm.core.model.StorageController
import dev.pocketvm.core.model.VmPlatform
import dev.pocketvm.core.model.VncAccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [VmConfigEntity] 与 [dev.pocketvm.core.model.VmConfig] 之间唯一的映射点。
 *
 * 枚举按 [Enum.name] 存成字符串；读到不认识的名字时回退到各枚举的默认值，
 * 而不是抛异常——与旧版 [dev.pocketvm.core.config.VmConfigStore] 的立场一致：
 * 一份坏配置绝不能让 App 无法启动（比如用户从别的版本拷回来一份 config，
 * 或将来某个枚举加了新值、旧版 App 先读到它）。
 *
 * 列表（端口转发、附加参数）存成 JSON 字符串；解析失败时返回空列表，
 * 同样不抛。
 */
object VmConfigConverters {

    private val json = Json { ignoreUnknownKeys = true }

    fun platformFromString(value: String): VmPlatform =
        runCatching { VmPlatform.valueOf(value) }.getOrDefault(VmPlatform.UEFI_ARM64)

    fun vncAccessFromString(value: String): VncAccess =
        runCatching { VncAccess.valueOf(value) }.getOrDefault(VncAccess.LOOPBACK)

    fun storageControllerFromString(value: String): StorageController =
        runCatching { StorageController.valueOf(value) }.getOrDefault(StorageController.NVME)

    fun cdTransportFromString(value: String): CdTransport =
        runCatching { CdTransport.valueOf(value) }.getOrDefault(CdTransport.AUTO)

    fun displayDeviceFromString(value: String): DisplayDevice =
        runCatching { DisplayDevice.valueOf(value) }.getOrDefault(DisplayDevice.RAMFB)

    fun launchModeFromString(value: String): LaunchMode =
        runCatching { LaunchMode.valueOf(value) }.getOrDefault(LaunchMode.AUTO)

    fun portForwardsToJson(value: List<PortForward>): String =
        json.encodeToString(value)

    fun portForwardsFromJson(value: String): List<PortForward> =
        runCatching { json.decodeFromString<List<PortForward>>(value) }.getOrDefault(emptyList())

    fun stringListToJson(value: List<String>): String =
        json.encodeToString(value)

    fun stringListFromJson(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}
