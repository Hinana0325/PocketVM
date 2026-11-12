import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起内置 Kotlin（built-in Kotlin），不再需要 kotlin-android 插件；
    // kotlin-compose / kotlin-serialization 仍需显式应用，版本与 kotlin 对齐。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Room 代码生成：KSP（2.2.10-2.0.2，与 Kotlin 2.2.10 配对）。
    alias(libs.plugins.ksp)
    // Hilt（P3）：@AndroidEntryPoint / @HiltViewModel / @HiltWorker 的字节码
    // 转换与代码生成靠它；hilt-compiler 与 androidx.hilt:hilt-compiler
    // 走下面的 ksp（与 Room 共用 KSP 2.2.10-2.0.2）。
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.pocketvm"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pocketvm"
        // memfd_create 是 API 30 符号，QEMU 执行层以 android30 为 target 编译。
        minSdk = 30
        targetSdk = 37
        versionCode = 58
        versionName = "0.11.0-qemu11.1.2-edk2026.08"

        // 调试快照要带构建工具链版本；从版本目录取，避免手抄漂移。
        buildConfigField("String", "AGP_VERSION", "\"${libs.versions.agp.get()}\"")
        buildConfigField("String", "KOTLIN_VERSION", "\"${libs.versions.kotlin.get()}\"")

        // The MVP only ships an arm64 execution layer. 32-bit devices cannot run
        // a 64-bit guest, so there is no point in building other ABIs.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 统一的侧载签名：多平台 / CI 用同一把 key，APK 才能互相覆盖升级。
    // 私钥不进仓库——本机放 ~/.pocketvm/pocketvm-signing.jks，CI 由 workflow
    // 从 Secret 解码到同一位置；也可用 -Ppvm.storeFile 或 PVM_KEYSTORE 指路径。
    // 找不到就回退 SDK 默认 debug key（新机器开箱能构建，只是签名不通用）。
    // 口令是固定的非机密值，保护对象是 .jks 文件本身。
    val sideloadKeystore: File? = listOfNotNull(
        (project.findProperty("pvm.storeFile") as String?)?.let { file(it) },
        System.getenv("PVM_KEYSTORE")?.let { file(it) },
        File(System.getProperty("user.home"), ".pocketvm/pocketvm-signing.jks"),
    ).firstOrNull { it.isFile }
    if (sideloadKeystore != null) {
        signingConfigs.create("sideload") {
            storeFile = sideloadKeystore
            storePassword = System.getenv("PVM_STORE_PASSWORD") ?: "pocketvm-sideload"
            keyAlias = System.getenv("PVM_KEY_ALIAS") ?: "pocketvm"
            keyPassword = System.getenv("PVM_KEY_PASSWORD") ?: "pocketvm-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
            // A sideloaded build can be signed by another machine's debug key and
            // therefore cannot be replaced in place. -PpvmSideBySide produces a
            // variant that installs alongside it instead, which is this device's
            // only route in while the shipping id must stay `dev.pocketvm`.
            if (project.hasProperty("pvmSideBySide")) applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // 侧载用的「性能版」：release 的运行时（非 debuggable，Compose 不带调试开销），
        // 但用 debug 签名和相同的 dev.pocketvm.debug 包名，可以直接覆盖已装的 debug 版。
        // 构建：./gradlew :app:assemblePerf -PpvmSideBySide=true
        create("perf") {
            initWith(getByName("release"))
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            if (project.hasProperty("pvmSideBySide")) applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // QEMU is shipped as libqemu-system-aarch64.so inside jniLibs so that the
    // loader places it on an exec-capable, read-only path. That only works when
    // the native libraries are actually extracted at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 执行层必须带 PAC-free 协程补丁：ARMv9 上未打补丁的 QEMU 会在 50–300 秒内
// 因 siglongjmp 的 autiasp 校验失败随机 SIGILL（exit=132，见 AGENTS.md）。
// 哈希与 scripts/patch-qemu-pac.py 的 PATCHED_SHA256 一致；新增段按 16 KB 对齐，
// 天玑 9xxx / Android 15+ 的 16 KB 页设备才能加载。
val qemuPacPatchedSha256 =
    "23f22b576fca0221dc261ed3eb0f887fdcac9b5ded3e98d294d6efad7a2f9f24"

val verifyQemuPacPatch by tasks.registering {
    val binary = file("src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so")
    inputs.file(binary)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(binary.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (digest != qemuPacPatchedSha256) {
            throw GradleException(
                "执行层没有 PAC 补丁（sha256=$digest）。\n" +
                    "重建 QEMU 后必须重新打补丁：\n" +
                    "  python scripts/patch-qemu-pac.py --input <新构建的 .so> " +
                    "--output android/app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so",
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyQemuPacPatch) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 官方导航：Navigation Compose + Kotlin Serialization 类型安全路由（P0 导航迁移）。
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    // 官方结构化存储：Room 2.7.2（P2 数据层）。room-ktx 自 2.7.0 起已并入
    // room-runtime，不再单独引；编译期代码生成走 KSP。
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Hilt（P3）：官方依赖注入。hilt-android 是运行时，hilt-compiler 是
    // 注解处理（KSP）；androidx.hilt 的两个扩展分别给 Compose 的
    // hiltViewModel() 与 WorkManager 的 @HiltWorker 用。
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    // WorkManager（P3）：日志清理定时任务。只要 work-runtime-ktx
    //（CoroutineWorker）；配置走 Hilt 的 HiltWorkerFactory，见 di/AppModule。
    implementation(libs.androidx.work.runtime.ktx)
    // SSH 控制台：mwiede 的 JSch 分支，纯 Java、支持 rsa-sha2/ed25519。
    implementation(libs.jsch)
    // 调试接口（docs/debug-api.md）：本地 HTTP，只读 + 回环 + token。
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // 路由级单测（ktor-server-test-host）；跑不起来就只测纯函数部分。
    testImplementation(libs.ktor.server.test.host)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
