import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.pocketvm"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pocketvm"
        // memfd_create 是 API 30 符号，QEMU 执行层以 android30 为 target 编译。
        minSdk = 30
        targetSdk = 35
        versionCode = 48
        versionName = "0.10.25-qemu11.1.2-edk2026.08"

        // The MVP only ships an arm64 execution layer. 32-bit devices cannot run
        // a 64-bit guest, so there is no point in building other ABIs.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
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
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // SSH 控制台：mwiede 的 JSch 分支，纯 Java、支持 rsa-sha2/ed25519。
    implementation(libs.jsch)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
