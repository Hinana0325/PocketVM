# PocketVM 调试接口开发文档（设计稿）

> 状态：**已实现（2026-11-12）**。Ktor 3.3.0（kotlin-stdlib 2.2.10，与项目 Kotlin 对齐）+ CIO，绑 `127.0.0.1:18080`；默认关闭，诊断中心「开发者调试」区可开关；除 `/health` 外都要 `X-PocketVM-Token`；start/stop/restart 额外受「允许远程控制」开关门控。
> 实现文件：`data/DebugPreferences.kt`（debug_prefs）、`debug/DebugApi.kt`（契约与快照模型）、`debug/DebugServerManager.kt`（服务器与路由）、`debug/DebugSnapshotExporter.kt`（快照导出）、`ui/DebugSettingsViewModel.kt` + 诊断中心 UI 区。单测 `DebugApiTest`（token/鉴权/钳制/快照字段与排除清单）；路由级测试未搭（依赖 Android 运行时），如实记录。

## 1. 目标

给 AI / 调试工具提供一个能调用的 App 内调试接口，方便开发期排查：

- 不用翻手机、不用接电脑抓 log，调一次接口就拿到状态、日志、诊断。
- 调试快照一键导出成 JSON 文件，发给 AI 就能直接读。
- 只能本机调用，不能从局域网调，不能执行任意命令。

对标：Chrome DevTools、Flipper 都是这个路子。

## 2. 红线（先划好，再写代码）

- 只绑 `127.0.0.1`，绝不绑 `0.0.0.0`，绝不上局域网。
- 默认关闭，用户在设置里打开才起服务。
- 除健康检查外，所有端点都要验 token，错了回 401。
- **不提供** QMP 透传（不许把任意 QEMU monitor 命令暴露出来）。
- **不提供** shell / 任意命令执行。
- **不提供** VM 自身日志、配置之外的文件读取。
- 快照与接口返回里**不含凭据、密钥**（排除清单见 §7）。

## 3. 架构

| 项 | 定法 |
|---|---|
| HTTP 服务 | Ktor `embeddedServer(CIO)`，按仓库官方技术栈规则用 JetBrains 官方方案，不手写 HTTP 解析，不引 NanoHTTPD |
| 地址 | `127.0.0.1:18080`（VNC 用 5900 段、SSH hostfwd 是客户机端口，都不冲突） |
| 包 | `dev.pocketvm.debug` |
| 服务管理 | `@Singleton` 的 `DebugServerManager`，由 `PocketVmApp` 跟随偏好启停 |
| 偏好 | 单独的 DataStore 实例 `debug_prefs`，与 `app_prefs`、`nav_state` 分开 |
| 序列化 | kotlinx.serialization，`ContentNegotiation` + json |
| 依赖 | 只加 server 端必需的：`ktor-server-core`、`ktor-server-cio`、`ktor-server-content-negotiation`、`ktor-serialization-kotlinx-json`；版本实现时上 Maven Central 核实与 Kotlin 2.2.10 / AGP 9.1.1 兼容的那一版，登记进 `libs.versions.toml`，不要凭记忆写 |

偏好三项（都在 `debug_prefs` 里）：

| 键 | 默认 | 说明 |
|---|---|---|
| `debug_enabled` | false | 接口总开关，关了服务就不起 |
| `debug_allow_control` | false | 远程控制开关，与接口开关分开；启停 VM 的端点只在它开着时可用 |
| `debug_token` | 首次开启时生成 | 32 字节随机 hex；不进 APK、不进日志 |

## 4. 端点

前缀 `/api/v1`，除 health 外都要请求头 `X-PocketVM-Token`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 不鉴权，只回 `{"ok":true}`，给 adb forward 探活用 |
| GET | `/api/v1/status` | App 版本、versionCode、VM 总数、正在运行的 VM、uptime |
| GET | `/api/v1/vms` | 列表：id、名字、状态、平台、磁盘、内存、CPU 数 |
| GET | `/api/v1/vms/{id}` | 单台配置详情 |
| GET | `/api/v1/vms/{id}/logs?lines=200` | 该 VM 最近一次运行日志的尾巴（复用现有 `LogTail`；`lines` 钳制在 1..1000） |
| GET | `/api/v1/diagnostics` | 看门狗状态、温控状态、性能快照、最近的 `AgentFeed` 事件（复用现有诊断类，别重写） |
| GET | `/api/v1/snapshot` | 完整快照 JSON（见 §7），与导出的快照用同一份模型 |
| POST | `/api/v1/vms/{id}/start` | 仅 `debug_allow_control` 开着时可用；走现有 `VmSession` / `VmViewModel` 的启动，不绕开校验；回 202 + 当前状态 |
| POST | `/api/v1/vms/{id}/stop` | 同上 |
| POST | `/api/v1/vms/{id}/restart` | 同上（先停后起，走同一条关机链路，不要直接 kill） |

TPM 状态端点这次不做：swtpm 代码在 `tpm-phase2` 分支，main 上没有，等合并后再接。

## 5. Token 管理

- 生成：`SecureRandom` 32 字节，转 hex。首次开启接口时生成并存入 `debug_prefs`。
- 查看：诊断页“开发者调试”区显示，点一下复制。
- 重新生成：诊断页按钮，生成后旧 token 立即失效。
- 校验：Ktor 拦截器统一验 `X-PocketVM-Token` 请求头；不接受 URL query 里的 token（query 会进日志）。
- 鉴权失败：401 + `{"error":"unauthorized"}`，不透露 token 是否存在。

## 6. UI 入口

放在诊断中心，新增“开发者调试”区：

- 开关：调试接口（默认关）
- 开关：允许远程控制（默认关；接口关着时置灰不可开）
- 文本：监听地址 `127.0.0.1:18080`（只读）
- Token：显示 + 复制按钮 + 重新生成按钮
- 按钮：导出调试快照（走 Android 分享，见 §7）

## 7. 快照导出

`DebugSnapshotExporter`，与 `GET /api/v1/snapshot` 用同一份序列化模型。

快照内容：status + vms + diagnostics + 各 VM 日志尾巴（每家最多 200 行）+ 构建信息（版本、QEMU/edk2 版本、AGP/Kotlin 版本）。

**排除清单（快照里绝不出现）**：debug token、SSH 密钥、VNC 密码（目前没有）、任何凭据、用户文件内容（只给路径和大小，不给内容）。

文件：`cacheDir/debug-snapshot-YYYYMMDD-HHmmss.json`，走 `ACTION_SEND` 分享（`application/json`）。不写外部存储公开目录。

## 8. 生命周期与失败处理

- 服务跟随 `debug_enabled` 的 Flow 启停，跑在 `Dispatchers.IO`。
- 端口被占：优雅失败（记日志 + 诊断页提示，不崩溃）。
- App 进程死了服务跟着死，不需要额外清理（没有 --daemon 形态）。
- 接口开关关着时，App 行为与现在完全一致（服务不启动、不占端口、不耗电）。

## 9. 测试（纯 JVM 单测，`app/src/test/`）

- token 生成：长度 64 hex、两次生成不一样。
- auth 拦截：无 token → 401；错 token → 401；对 token → 放行。
- `lines` 钳制：0 → 1，2000 → 1000。
- 快照 JSON 结构：字段齐全、排除清单里的字段一个都没有。
- 能用 `ktor-server-test-host` 的话加一两个路由级测试（`testImplementation` 依赖）；跑不起来就只测纯函数部分，如实写。
- 本机无 JDK/SDK 时不许说“已验证通过”，写“未编译，需 CI 验证”。

## 10. 使用方法

```bash
# 电脑上转发端口（手机连着 adb）
adb forward tcp:18080 tcp:18080

# 探活（不用 token）
curl http://127.0.0.1:18080/api/v1/health

# 查状态（替换成诊断页显示的 token）
curl -H "X-PocketVM-Token: <token>" http://127.0.0.1:18080/api/v1/status

# 拉快照
curl -H "X-PocketVM-Token: <token>" http://127.0.0.1:18080/api/v1/snapshot -o snapshot.json

# Termux 里不用转发，直接调 127.0.0.1:18080
```

Muse 够不着手机的 loopback：远程调试的桥是快照文件——手机上一键导出，发给 Muse 就能直接读。

## 11. 安全模型（一句话版）

能调接口的只有三类：本机上的进程、adb 转发后的本机电脑、Termux。局域网、公网都够不着。token 泄露的 blast radius 是“本机能读状态、能启停 VM”，所以 token 只在本机显示、可一键换掉；控制开关默认关着。

## 12. 与现有代码的接点

| 需要的数据 | 现有来源 | 备注 |
|---|---|---|
| VM 列表与配置 | Room `VmConfigRepository` | 实时配置，不读旧 config.json |
| 运行状态 | `VmSession`（`@Singleton`） | 不要自己再维护一份状态 |
| 日志尾巴 | `LogTail` | 已有 API，直接用 |
| 诊断事件 | `AgentFeed`、看门狗、温控、性能类 | 只读，不改 |
| 启停 | `VmSession` / `VmViewModel` | 走正式链路，含校验 |
| 偏好存储 | 新建 `debug_prefs` | 不混进 `app_prefs` |

## 13. 不做清单

- 云端同步（快照推 CloudBase、Muse 远程读）：下一档，这次不做。
- TPM 状态端点：等 `tpm-phase2` 合并后再接。
- 局域网模式：不做（VNC 的教训）。
- 任意文件读取、任意命令执行、QMP 透传：永远不做。

## 14. 依赖与体积

- 新增依赖：§3 表里那 4 个 Ktor server 依赖（+ 序列化运行时已在用）。
- APK 体积影响：实现后以 CI 产物为准，在提交说明里写实测数字，不要估。
