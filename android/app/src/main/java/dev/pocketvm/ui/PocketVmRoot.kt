package dev.pocketvm.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketvm.core.model.VmRuntimeState
import java.io.File

@Composable
fun PocketVmRoot(vm: VmViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice, withDismissAction = true)
        vm.clearNotice(notice)
    }

    // Ask only when a VM actually starts: the notification is what keeps it alive.
    // 只在「从没运行到开始运行」或换了一台 VM 时请求一次通知权限；
    // Running.stage 会随引导阶段变化，不能拿整个 runtime 对象当 key。
    val runningVmId = (state.runtime as? VmRuntimeState.Running)?.vmId
    LaunchedEffect(runningVmId) {
        if (runningVmId != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.shareFile) {
        val file = state.shareFile ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent(context, file), "分享诊断包"))
        }
        vm.consumeShare()
    }

    // Top-level destinations get a top app bar; the list screen leads with its
    // hero header instead, so it stays clean. Create/Console paint their own
    // header inside the scroll area.
    val showTopBar = when (state.screen) {
        Screen.Environment, Screen.About -> true
        else -> false
    }
    val topTitle = when (state.screen) {
        Screen.Environment -> "执行环境"
        Screen.About -> "关于"
        else -> ""
    }
    val isTopLevel = state.screen.isTopLevel

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (showTopBar) PocketTopBar(title = topTitle)
            },
            bottomBar = {
                if (isTopLevel) PocketNavBar(current = state.screen, onSelect = { vm.navigate(it) })
            },
            floatingActionButton = {
                if (state.screen is Screen.List) {
                    ExtendedFloatingActionButton(
                        onClick = vm::newVm,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(18.dp),
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 8.dp,
                            pressedElevation = 12.dp,
                        ),
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("新建虚拟机") },
                    )
                }
            },
        ) { padding ->
        // 自绘 TopAppBar 的页面（新建/控制台）顶部 inset 由 TopAppBar 自己处理，
        // 再叠加 Scaffold 的顶部 padding 会多出一条状态栏高度的空白。
        val layoutDirection = LocalLayoutDirection.current
        val topBarScreenPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            top = 0.dp,
            end = padding.calculateEndPadding(layoutDirection),
            bottom = padding.calculateBottomPadding(),
        )
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = state.screen,
                transitionSpec = {
                    val forward = targetState is Screen.Console || targetState is Screen.Create
                    (fadeIn(tween(240)) + slideInHorizontally { if (forward) it / 8 else -it / 8 })
                        .togetherWith(
                            fadeOut(tween(160)) + slideOutHorizontally { if (forward) -it / 12 else it / 12 },
                        )
                },
                label = "screen",
            ) { screen ->
            when (screen) {
                Screen.List -> VmListScreen(
                    state = state,
                    contentPadding = padding,
                    onNew = vm::newVm,
                    onOpen = vm::openConsole,
                    onEdit = vm::editVm,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onDelete = vm::deleteVm,
                    onProbe = vm::probeEnvironment,
                )

                Screen.Create -> CreateVmScreen(
                    state = state,
                    onUpdate = vm::updateDraft,
                    onSave = vm::saveDraft,
                    onBack = vm::back,
                    onImportIso = vm::importIso,
                    onImportDriverIso = vm::importDriverIso,
                    onImportFirmware = vm::importFirmware,
                    onSetPlatform = vm::setPlatform,
                    onImportApple = vm::importAppleBundle,
                    contentPadding = topBarScreenPadding,
                )

                is Screen.Console -> {
                    val target = state.vms.firstOrNull { it.id == screen.vmId }
                    if (target == null) {
                        Text("虚拟机已删除", modifier = Modifier.padding(24.dp))
                    } else {
                        ConsoleScreen(
                            state = state,
                            vm = target,
                            contentPadding = topBarScreenPadding,
                            onStart = { vm.start(target.id) },
                            onStop = { vm.stop(target.id) },
                            onRefresh = vm::refreshLog,
                            onExport = { vm.exportDiagnostics(target.id) },
                            onEject = { vm.ejectIso(target.id) },
                            onGuestKey = { code, label -> vm.sendGuestKey(target.id, code, label) },
                            onGuestCombo = { codes -> vm.sendGuestCombo(target.id, codes) },
                            onGuestText = { text -> vm.typeGuestText(target.id, text) },
                            onBootFromShell = { vm.bootFromShell(target.id) },
                            onSshConnect = vm::sshConnect,
                            onSshDisconnect = vm::sshDisconnect,
                            onSshRun = vm::sshRun,
                            onSshClear = vm::sshClearOutput,
                            onSshOpenShell = vm::sshOpenShell,
                            onSshCloseShell = vm::sshCloseShell,
                            onSshShellSend = vm::sshShellSend,
                            onSshShellCtrlC = vm::sshShellCtrlC,
                            onBack = vm::back,
                        )
                    }
                }

                Screen.Environment -> EnvironmentScreen(
                    state = state,
                    modifier = Modifier.padding(padding),
                    onProbe = vm::probeEnvironment,
                )

                Screen.About -> AboutScreen(
                    state = state,
                    modifier = Modifier.padding(padding),
                )
            }
            }
        }
    }
    }
}

private fun shareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "PocketVM 诊断包")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
