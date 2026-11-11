package dev.pocketvm.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pocketvm.core.model.VmRuntimeState
import dev.pocketvm.data.AppPreferences
import dev.pocketvm.data.ThemeMode
import dev.pocketvm.navigation.About
import dev.pocketvm.navigation.AppNavHost
import dev.pocketvm.navigation.CreateBasic
import dev.pocketvm.navigation.Environment
import dev.pocketvm.navigation.Home
import dev.pocketvm.navigation.LastRouteStore
import dev.pocketvm.navigation.routeForKey
import dev.pocketvm.navigation.routeKey
import java.io.File
import kotlinx.coroutines.launch

/**
 * P1 导航：官方 Navigation Compose 是唯一的导航。
 *
 * 旧的 sealed interface Screen、AnimatedContent（LegacyScreenContent）、
 * USE_NAV_HOST 开关、VmViewModel 的 navigate/back 状态机已全部拆掉——
 * 导航状态只在 NavController 的回退栈里；VmViewModel 只剩业务状态
 * （虚拟机、QEMU 进程、VNC 连接、日志、诊断数据）。
 *
 * 顶层判定、顶栏、底部栏、FAB 全部从当前路由读（hasRoute），不再读
 * state.screen。系统返回键交给 NavController 自己处理（逐级 pop）；
 * 各屏自己的 BackHandler（向导弃稿确认、配置页弃稿确认）优先于它。
 *
 * 杀进程恢复：启动时从 DataStore（nav_state）读上次的路由 key，
 * 恢复到用户正在看的页面（向导步骤不持久化，恢复时一定落在列表或
 * 详情/控制台/配置/诊断页）。读到之前显示加载态，不先挂一个假首页。
 */
@Composable
fun PocketVmRoot(
    vm: VmViewModel = hiltViewModel(),
    // CreateVmViewModel 需要 VmViewModel（辅助注入）：Hilt 不能直接注入
    // 另一个 ViewModel，所以 vm 在这里由 creationCallback 传给工厂；
    // 其余依赖由 Hilt 注入。手写的 Factory 已删（见 CreateVmViewModel）。
    createVm: CreateVmViewModel = hiltViewModel<CreateVmViewModel, CreateVmViewModel.Factory> { factory ->
        factory.create(vm)
    },
    // 偏好与上次路由由 MainActivity 从 Hilt 图里注入后传进来；Composable
    // 本身不是 Hilt 入口，不能直接 @Inject。
    appPreferences: AppPreferences,
    lastRouteStore: LastRouteStore,
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // P2：主题偏好（DataStore app_prefs）在这里读、下传给环境页的选择器；
    // 真正换主题在 MainActivity（PocketVmTheme 包在 Root 外面）。
    val themeMode by appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val prefsScope = rememberCoroutineScope()
    val onThemeMode: (ThemeMode) -> Unit = { mode ->
        prefsScope.launch { appPreferences.setThemeMode(mode) }
    }
    val snackbar = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    // 杀进程恢复：DataStore 里读上次页面；null = 还没读到，先显示加载态。
    var startDestination by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        startDestination = routeForKey(lastRouteStore.readLastRouteKey()) ?: Home
    }

    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val destination = currentEntry?.destination
    val isHome = destination?.hasRoute<Home>() == true
    val isTopLevel = isHome ||
        destination?.hasRoute<Environment>() == true ||
        destination?.hasRoute<About>() == true
    val showTopBar = destination?.hasRoute<Environment>() == true ||
        destination?.hasRoute<About>() == true
    val topTitle = when {
        destination?.hasRoute<Environment>() == true -> "执行环境"
        destination?.hasRoute<About>() == true -> "关于"
        else -> ""
    }

    // 每次切页都把当前路由 key 存进 DataStore，供下次启动恢复。
    // 向导步骤的 routeKey() 返回 null，不存——恢复时不会落回向导。
    LaunchedEffect(currentEntry) {
        currentEntry?.let { entry ->
            lastRouteStore.saveLastRouteKey(entry.routeKey())
        }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (showTopBar) PocketTopBar(title = topTitle)
            },
            bottomBar = {
                if (isTopLevel) PocketNavBar(
                    current = when {
                        isHome -> TopLevelDestination.HOME
                        destination?.hasRoute<Environment>() == true -> TopLevelDestination.ENVIRONMENT
                        destination?.hasRoute<About>() == true -> TopLevelDestination.ABOUT
                        else -> TopLevelDestination.HOME
                    },
                    onSelect = { dest ->
                        val route = when (dest) {
                            TopLevelDestination.HOME -> Home
                            TopLevelDestination.ENVIRONMENT -> Environment
                            TopLevelDestination.ABOUT -> About
                        }
                        navController.navigate(route) {
                            popUpTo(Home) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            },
            floatingActionButton = {
                if (isHome) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            createVm.startNew()
                            navController.navigate(CreateBasic) { launchSingleTop = true }
                        },
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
            when (val start = startDestination) {
                null -> {
                    // 还没读到上次页面：先显示加载态，读到再挂 NavHost。
                    // 不先挂一个假首页——否则恢复时会先闪一下列表。
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    AppNavHost(
                        navController = navController,
                        startDestination = start,
                        state = state,
                        vm = vm,
                        createVm = createVm,
                        padding = padding,
                        topBarScreenPadding = topBarScreenPadding,
                        themeMode = themeMode,
                        onThemeMode = onThemeMode,
                        modifier = Modifier.fillMaxSize(),
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
