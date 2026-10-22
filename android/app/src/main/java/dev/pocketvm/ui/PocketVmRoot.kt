package dev.pocketvm.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
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
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice, withDismissAction = true)
        vm.clearNotice()
    }

    // Ask only when a VM actually starts: the notification is what keeps it alive.
    LaunchedEffect(state.runtime) {
        if (state.runtime is VmRuntimeState.Running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val screen = state.screen) {
                Screen.List -> VmListScreen(
                    state = state,
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
                    onImportFirmware = vm::importFirmware,
                )

                is Screen.Console -> {
                    val target = state.vms.firstOrNull { it.id == screen.vmId }
                    if (target == null) {
                        Text("虚拟机已删除", modifier = Modifier.padding(24.dp))
                    } else {
                        ConsoleScreen(
                            state = state,
                            vm = target,
                            onStart = { vm.start(target.id) },
                            onStop = { vm.stop(target.id) },
                            onRefresh = vm::refreshLog,
                            onExport = { vm.exportDiagnostics(target.id) },
                            onEject = { vm.ejectIso(target.id) },
                            onBack = vm::back,
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
