package dev.pocketvm.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.data.ThemeMode
import kotlinx.coroutines.launch
import dev.pocketvm.ui.AboutScreen
import dev.pocketvm.ui.ConsoleScreen
import dev.pocketvm.ui.CreateBasicScreen
import dev.pocketvm.ui.CreateDevicesScreen
import dev.pocketvm.ui.CreateHardwareScreen
import dev.pocketvm.ui.CreateReviewScreen
import dev.pocketvm.ui.CreateRuntimeScreen
import dev.pocketvm.ui.CreateVmViewModel
import dev.pocketvm.ui.DiagnosticDetailScreen
import dev.pocketvm.ui.EnvironmentScreen
import dev.pocketvm.ui.UiState
import dev.pocketvm.ui.VmConfigCategoryScreen
import dev.pocketvm.ui.VmConfigScreen
import dev.pocketvm.ui.VmConnectionScreen
import dev.pocketvm.ui.VmDetailScreen
import dev.pocketvm.ui.VmDiagnosticsScreen
import dev.pocketvm.ui.VmListScreen
import dev.pocketvm.ui.VmSnapshotsScreen
import dev.pocketvm.ui.VmViewModel

/**
 * P1 官方导航：全部目的地都挂在 NavHost 上，导航状态只在 NavController
 * 的回退栈里。VmViewModel 的 sealed interface Screen、navigate/back
 * 状态机已拆掉；这里只读业务状态（state.vms / state.runtime），
 * 用 navController.navigate / popBackStack 做跳转。
 *
 * 路由只传 VM ID（字符串），不传整个 VM 对象；每页进场时按 ID 从
 * state.vms 里取最新配置，取不到就显示「虚拟机不存在或已删除」
 * （删掉之后从详情/控制台/配置/诊断页返回时不会崩）。
 *
 * 层级（返回逐级 pop，不再一律回首页）：
 * Home → VmDetail → { VmConsole, VmConfigHome, VmDiagnostics, VmConnection }
 * VmConfigHome → VmConfigDetail（分类）
 * VmDiagnostics → DiagnosticDetail（子页）
 * Home → CreateBasic → CreateHardware → CreateDevices → CreateRuntime → CreateReview
 *
 * 实时监控数据仍由 VmViewModel 统一订阅（state.diagnostics / state.runtime），
 * 各页只读自己需要的那部分；切页不重新订阅、不中断采集。
 * QEMU 运行逻辑（VmSession、QemuCommandBuilder、看门狗、性能调度器）
 * 一律不碰，这里只接线。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Any,
    state: UiState,
    vm: VmViewModel,
    createVm: CreateVmViewModel,
    padding: PaddingValues,
    topBarScreenPadding: PaddingValues,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 保存草稿（向导/配置共用）：存盘 → 刷新列表 → 提示。文案与旧
    // VmViewModel.saveDraft 一致；导航由调用方接在后面。
    // P2：写 Room 是 suspend，起协程写完再跳转——保存没落盘就 pop 回列表，
    // 用户下一秒杀进程会丢配置。
    val scope = rememberCoroutineScope()
    val saveDraft: ((dev.pocketvm.ui.SaveResult) -> Unit) -> Unit = { onSaved ->
        scope.launch {
            val result = createVm.saveDraft()
            if (result != null) {
                vm.refreshVms()
                vm.postNotice(
                    if (result.errorCount > 0) {
                        "已保存 ${result.config.name}，但还有 ${result.errorCount} 个错误，需修复后才能启动"
                    } else {
                        "已保存 ${result.config.name}"
                    },
                )
                onSaved(result)
            }
        }
    }
    // 向导保存 → 回列表；配置保存 → 回详情页（管理中心）。
    val saveWizard: () -> Unit = {
        saveDraft { navController.popBackStack(Home, inclusive = false) }
    }
    val saveConfig: (String) -> Unit = { vmId ->
        saveDraft { navController.popBackStack(VmDetail(vmId), inclusive = false) }
    }
    val pop: () -> Unit = { navController.popBackStack() }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { NavTransitions.enter() },
        exitTransition = { NavTransitions.exit() },
        popEnterTransition = { NavTransitions.popEnter() },
        popExitTransition = { NavTransitions.popExit() },
    ) {
        composable<Home> {
            VmListScreen(
                state = state,
                contentPadding = padding,
                onNew = {
                    createVm.startNew()
                    navController.navigate(CreateBasic) { launchSingleTop = true }
                },
                // 点卡片进详情页（管理中心），不再直接进控制台。
                onOpen = { vmId ->
                    navController.navigate(VmDetail(vmId)) { launchSingleTop = true }
                },
                onEdit = { vmId ->
                    createVm.openConfig(vmId)
                    navController.navigate(VmConfigHome(vmId)) { launchSingleTop = true }
                },
                onStart = { vmId ->
                    vm.start(vmId)
                    navController.navigate(VmConsole(vmId)) { launchSingleTop = true }
                },
                onStop = vm::stop,
                onDelete = vm::deleteVm,
                onProbe = vm::probeEnvironment,
            )
        }

        // ---- 创建向导：5 步，草稿在 CreateVmViewModel 里，切换步骤不丢 ----
        composable<CreateBasic> {
            CreateBasicScreen(
                state = state,
                createVm = createVm,
                contentPadding = topBarScreenPadding,
                onNext = { navController.navigate(CreateHardware) },
                onExit = pop,
            )
        }
        composable<CreateHardware> {
            CreateHardwareScreen(
                state = state,
                createVm = createVm,
                contentPadding = topBarScreenPadding,
                onPrev = pop,
                onNext = { navController.navigate(CreateDevices) },
                onExit = pop,
            )
        }
        composable<CreateDevices> {
            CreateDevicesScreen(
                state = state,
                createVm = createVm,
                contentPadding = topBarScreenPadding,
                onPrev = pop,
                onNext = { navController.navigate(CreateRuntime) },
                onExit = pop,
            )
        }
        composable<CreateRuntime> {
            CreateRuntimeScreen(
                state = state,
                createVm = createVm,
                contentPadding = topBarScreenPadding,
                onPrev = pop,
                onNext = { navController.navigate(CreateReview) },
                onExit = pop,
            )
        }
        composable<CreateReview> {
            CreateReviewScreen(
                state = state,
                createVm = createVm,
                contentPadding = topBarScreenPadding,
                onPrev = pop,
                onSave = saveWizard,
                onExit = pop,
            )
        }

        // ---- 虚拟机详情：管理中心 ----
        composable<VmDetail> { backStackEntry ->
            val vmId = backStackEntry.toRoute<VmDetail>().vmId
            val target = state.vms.firstOrNull { it.id == vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                VmDetailScreen(
                    state = state,
                    vm = target,
                    contentPadding = topBarScreenPadding,
                    onBack = pop,
                    onOpenConsole = {
                        navController.navigate(VmConsole(vmId)) { launchSingleTop = true }
                    },
                    onOpenConfig = {
                        createVm.openConfig(vmId)
                        navController.navigate(VmConfigHome(vmId)) { launchSingleTop = true }
                    },
                    onOpenSnapshots = {
                        navController.navigate(VmSnapshots(vmId)) { launchSingleTop = true }
                    },
                    onClone = { vm.cloneVm(vmId) },
                    onOpenDiagnostics = {
                        navController.navigate(VmDiagnostics(vmId)) { launchSingleTop = true }
                    },
                    onOpenConnection = {
                        navController.navigate(VmConnection(vmId)) { launchSingleTop = true }
                    },
                    onStart = { vm.start(vmId) },
                    onStop = { vm.stop(vmId) },
                    onDelete = {
                        vm.deleteVm(vmId)
                        navController.popBackStack(Home, inclusive = false)
                    },
                )
            }
        }

        composable<VmConsole> { backStackEntry ->
            val vmId = backStackEntry.toRoute<VmConsole>().vmId
            val target = state.vms.firstOrNull { it.id == vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                LaunchedEffect(vmId) { vm.openConsole(vmId) }
                ConsoleScreen(
                    state = state,
                    vm = target,
                    contentPadding = topBarScreenPadding,
                    onStart = { vm.start(target.id) },
                    onStop = { vm.stop(target.id) },
                    onRefresh = { vm.refreshLog(target.id) },
                    onExport = { vm.exportDiagnostics(target.id) },
                    onEject = { vm.ejectIso(target.id) },
                    onGuestKey = { code, label -> vm.sendGuestKey(target.id, code, label) },
                    onGuestCombo = { codes -> vm.sendGuestCombo(target.id, codes) },
                    onGuestText = { text -> vm.typeGuestText(target.id, text) },
                    onBootFromShell = { vm.bootFromShell(target.id) },
                    onSerialSend = { text, enter -> vm.sendSerial(target.id, text, enter) },
                    onSshConnect = vm::sshConnect,
                    onSshDisconnect = vm::sshDisconnect,
                    onSshRun = vm::sshRun,
                    onSshClear = vm::sshClearOutput,
                    onSshOpenShell = vm::sshOpenShell,
                    onSshCloseShell = vm::sshCloseShell,
                    onSshShellSend = vm::sshShellSend,
                    onSshShellCtrlC = vm::sshShellCtrlC,
                    onBack = pop,
                    onOpenDiagnostics = {
                        navController.navigate(VmDiagnostics(vmId)) { launchSingleTop = true }
                    },
                    onOpenConnection = {
                        navController.navigate(VmConnection(vmId)) { launchSingleTop = true }
                    },
                    onRequestBatteryWhitelist = vm::requestBatteryWhitelist,
                )
            }
        }

        // ---- VNC 连接信息 ----
        composable<VmConnection> { backStackEntry ->
            val vmId = backStackEntry.toRoute<VmConnection>().vmId
            val target = state.vms.firstOrNull { it.id == vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                val running = state.runtimes[vmId] is VmRuntimeState.Running
                VmConnectionScreen(
                    vm = target,
                    running = running,
                    localIp = state.localIp,
                    networkAvailable = state.networkAvailable,
                    contentPadding = topBarScreenPadding,
                    onBack = pop,
                )
            }
        }

        // ---- 配置：入口列表 + 5 个分类页 ----
        composable<VmConfigHome> { backStackEntry ->
            val vmId = backStackEntry.toRoute<VmConfigHome>().vmId
            val target = state.vms.firstOrNull { it.id == vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                // 进程恢复的安全网：草稿不在内存里（或不是这台）时从磁盘重读。
                // 正常从详情页进来时 openConfig 是 no-op（草稿已是这台且 editing）。
                LaunchedEffect(vmId) { createVm.openConfig(vmId) }
                VmConfigScreen(
                    state = state,
                    createVm = createVm,
                    vm = target,
                    contentPadding = topBarScreenPadding,
                    onOpenCategory = { category ->
                        navController.navigate(VmConfigDetail(vmId, category))
                    },
                    onBack = pop,
                )
            }
        }
        composable<VmConfigDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<VmConfigDetail>()
            val target = state.vms.firstOrNull { it.id == route.vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                VmConfigCategoryScreen(
                    state = state,
                    createVm = createVm,
                    vmId = route.vmId,
                    category = route.category,
                    contentPadding = topBarScreenPadding,
                    onBack = pop,
                    onSave = { saveConfig(route.vmId) },
                )
            }
        }

        // ---- 快照与备份 ----
        composable<VmSnapshots> { backStackEntry ->
            val vmId = backStackEntry.toRoute<VmSnapshots>().vmId
            val target = state.vms.firstOrNull { it.id == vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                VmSnapshotsScreen(
                    vm = target,
                    contentPadding = topBarScreenPadding,
                    onBack = pop,
                )
            }
        }

        // ---- 诊断：入口列表 + 5 个子页 ----
        composable<VmDiagnostics> { backStackEntry ->
            val vmId = backStackEntry.toRoute<VmDiagnostics>().vmId
            val target = state.vms.firstOrNull { it.id == vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                LaunchedEffect(vmId) { vm.focus(vmId) }
                VmDiagnosticsScreen(
                    state = state,
                    vm = target,
                    contentPadding = topBarScreenPadding,
                    onOpenDetail = { type ->
                        navController.navigate(DiagnosticDetail(vmId, type))
                    },
                    onBack = pop,
                )
            }
        }
        composable<DiagnosticDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<DiagnosticDetail>()
            val target = state.vms.firstOrNull { it.id == route.vmId }
            if (target == null) {
                Text("虚拟机不存在或已删除", modifier = Modifier.padding(24.dp))
            } else {
                DiagnosticDetailScreen(
                    state = state,
                    vm = target,
                    type = route.type,
                    contentPadding = topBarScreenPadding,
                    onBack = pop,
                    onStart = { vm.start(route.vmId) },
                    onStop = { vm.stop(route.vmId) },
                    onExport = { vm.exportDiagnostics(route.vmId) },
                    // 旧版是控制台内切到「日志」页；现在诊断是独立页，
                    // 先回控制台（日志页在控制台里点一下），不再跨页切 tab。
                    onOpenLogs = {
                        navController.navigate(VmConsole(route.vmId)) {
                            launchSingleTop = true
                        }
                    },
                    onRequestBatteryWhitelist = vm::requestBatteryWhitelist,
                )
            }
        }

        composable<Environment> {
            EnvironmentScreen(
                state = state,
                themeMode = themeMode,
                onThemeMode = onThemeMode,
                modifier = Modifier.padding(padding),
                onProbe = vm::probeEnvironment,
            )
        }

        composable<About> {
            AboutScreen(
                state = state,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
