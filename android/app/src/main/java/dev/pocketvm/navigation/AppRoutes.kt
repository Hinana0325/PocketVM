package dev.pocketvm.navigation

import kotlinx.serialization.Serializable

/**
 * P1 导航路由（类型安全路由，Navigation Compose 2.8.9）。
 *
 * 规则：
 * - 导航只传 VM ID，不传整个 VM 对象；目标页从 VmViewModel.ui.vms 里按 ID 取。
 * - 路由之间不要搞公共 sealed interface：NavController.navigate(route: T)
 *   要求每个路由都是 @Serializable 的 data class / data object。
 * - 配置分类与诊断子页用枚举做参数（ConfigCategory / DiagnosticType），
 *   比字符串更安全，拼写错误在编译期就能发现。
 * - VmConfigHome 叫这个名字而不是 VmConfig，是为了避开
 *   dev.pocketvm.core.model.VmConfig（虚拟机配置数据类）的同名冲突。
 *
 * 层级（P1）：
 * - 首页 Home → VmDetail（虚拟机详情，管理中心）
 *   → VmConsole（控制台） / VmConfigHome（配置入口） / VmDiagnostics（诊断入口）
 *   → VmConnection（VNC 连接信息） / VmConfigDetail（配置分类页） / DiagnosticDetail（诊断子页）
 * - 新建向导：CreateBasic → CreateHardware → CreateDevices → CreateRuntime → CreateReview
 * - 顶层：Home / Environment / About（底部导航）。
 */

@Serializable
data object Home

@Serializable
data object Environment

@Serializable
data object About

// ---- 新建向导（5 步） ----

@Serializable
data object CreateBasic

@Serializable
data object CreateHardware

@Serializable
data object CreateDevices

@Serializable
data object CreateRuntime

@Serializable
data object CreateReview

// ---- 虚拟机详情与控制台 ----

/** 虚拟机详情页：管理中心（基本信息、快捷操作、运行概览、管理入口）。 */
@Serializable
data class VmDetail(val vmId: String)

@Serializable
data class VmConsole(val vmId: String)

/** VNC 连接信息页：地址、Display、密码、adb forward、端口转发。 */
@Serializable
data class VmConnection(val vmId: String)

// ---- 配置（入口 + 5 个分类页） ----

/** 配置分类：处理器 / 内存 / 存储 / 设备 / 运行环境。 */
@Serializable
enum class ConfigCategory(val label: String) {
    PROCESSOR("处理器"),
    MEMORY("内存"),
    STORAGE("存储"),
    DEVICE("设备"),
    RUNTIME("运行环境"),
    ;
}

/** 配置入口页：按分类列出 5 个入口。 */
@Serializable
data class VmConfigHome(val vmId: String)

/** 配置分类页：一个分类一页，只改该分类的字段。 */
@Serializable
data class VmConfigDetail(val vmId: String, val category: ConfigCategory)

// ---- 诊断（入口 + 5 个子页） ----

/** 诊断分类：看门狗 / 性能 / 卡顿 / 温控与绑核 / 会话报告。 */
@Serializable
enum class DiagnosticType(val label: String) {
    WATCHDOG("看门狗"),
    PERFORMANCE("性能"),
    STUTTER("卡顿"),
    THERMAL("温控与绑核"),
    REPORT("会话报告"),
    ;
}

/** 诊断入口页：健康判定摘要 + 5 个子页入口。 */
@Serializable
data class VmDiagnostics(val vmId: String)

/** 诊断子页：一个分类一页。 */
@Serializable
data class DiagnosticDetail(val vmId: String, val type: DiagnosticType)
