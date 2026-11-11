package dev.pocketvm.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.pocketvm.core.model.VmConfig

/**
 * 虚拟机配置的 Room 行（P2 数据层）：与 [VmConfig] 字段一一对应。
 *
 * 枚举存 [Enum.name] 字符串、列表存 JSON 字符串，映射只走
 * [VmConfigConverters]——Entity 本身只含 Room 能直接存的类型
 *（String / Int / Long / Boolean / Boolean?），不挂 @TypeConverter，
 * 避免转换器注册漏配导致编译期才发现。
 *
 * 注意 [createdAtEpochMs] 映射时原样搬运，不重新生成：列表按创建时间倒序，
 * 重新生成会打乱已有虚拟机的顺序。
 */
@Entity(tableName = "vm_configs")
data class VmConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val platform: String,
    val cpuCores: Int,
    val memoryMb: Int,
    val diskSizeGb: Int,
    val isoPath: String?,
    val driverIsoPath: String?,
    val firmwareCodePath: String?,
    val bootFromCd: Boolean,
    val vncPort: Int,
    val vncAccess: String,
    val vncPassword: String?,
    val storageController: String,
    val cdTransport: String,
    val usbInput: Boolean,
    val usbDisabled: Boolean,
    val displayDevice: String,
    val serialRecovery: Boolean,
    val enableNet: Boolean?,
    val portForwardsJson: String,
    val rng: Boolean?,
    val useBootImage: Boolean,
    val cpuModel: String?,
    val launchMode: String,
    val extraArgsJson: String,
    val createdAtEpochMs: Long,
) {
    fun toVmConfig(): VmConfig = VmConfig(
        id = id,
        name = name,
        platform = VmConfigConverters.platformFromString(platform),
        cpuCores = cpuCores,
        memoryMb = memoryMb,
        diskSizeGb = diskSizeGb,
        isoPath = isoPath,
        driverIsoPath = driverIsoPath,
        firmwareCodePath = firmwareCodePath,
        bootFromCd = bootFromCd,
        vncPort = vncPort,
        vncAccess = VmConfigConverters.vncAccessFromString(vncAccess),
        vncPassword = vncPassword,
        storageController = VmConfigConverters.storageControllerFromString(storageController),
        cdTransport = VmConfigConverters.cdTransportFromString(cdTransport),
        usbInput = usbInput,
        usbDisabled = usbDisabled,
        displayDevice = VmConfigConverters.displayDeviceFromString(displayDevice),
        serialRecovery = serialRecovery,
        enableNet = enableNet,
        portForwards = VmConfigConverters.portForwardsFromJson(portForwardsJson),
        rng = rng,
        useBootImage = useBootImage,
        cpuModel = cpuModel,
        launchMode = VmConfigConverters.launchModeFromString(launchMode),
        extraArgs = VmConfigConverters.stringListFromJson(extraArgsJson),
        createdAtEpochMs = createdAtEpochMs,
    )

    companion object {
        fun fromVmConfig(config: VmConfig): VmConfigEntity = VmConfigEntity(
            id = config.id,
            name = config.name,
            platform = config.platform.name,
            cpuCores = config.cpuCores,
            memoryMb = config.memoryMb,
            diskSizeGb = config.diskSizeGb,
            isoPath = config.isoPath,
            driverIsoPath = config.driverIsoPath,
            firmwareCodePath = config.firmwareCodePath,
            bootFromCd = config.bootFromCd,
            vncPort = config.vncPort,
            vncAccess = config.vncAccess.name,
            vncPassword = config.vncPassword,
            storageController = config.storageController.name,
            cdTransport = config.cdTransport.name,
            usbInput = config.usbInput,
            usbDisabled = config.usbDisabled,
            displayDevice = config.displayDevice.name,
            serialRecovery = config.serialRecovery,
            enableNet = config.enableNet,
            portForwardsJson = VmConfigConverters.portForwardsToJson(config.portForwards),
            rng = config.rng,
            useBootImage = config.useBootImage,
            cpuModel = config.cpuModel,
            launchMode = config.launchMode.name,
            extraArgsJson = VmConfigConverters.stringListToJson(config.extraArgs),
            createdAtEpochMs = config.createdAtEpochMs,
        )
    }
}
