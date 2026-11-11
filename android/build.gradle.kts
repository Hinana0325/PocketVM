plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 起内置 Kotlin：kotlin-android 插件不再应用（与新 DSL 不兼容），
    // Kotlin 版本由 AGP 的 KGP 运行时依赖提供；kotlin-compose /
    // kotlin-serialization 仍在 app 模块显式应用，版本见 libs.versions.toml。
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Hilt Gradle 插件（P3）：只声明版本，应用在 app 模块。
    alias(libs.plugins.hilt) apply false
}
