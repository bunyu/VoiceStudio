import groovy.json.JsonSlurper
plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
// A separate, focused app: open a book and listen. It shares VoiceStudio's on-device engines
// (core:local) and version, but has its own app id, storage and launcher entry.
val repository = rootDir.resolve("../..")
val appVersion = (JsonSlurper().parse(repository.resolve("package.json")) as Map<*, *>)["version"].toString()
val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)(?:-beta\.\d+)?""").matchEntire(appVersion)?.destructured
    ?: error("Unsupported app version for Android: $appVersion")
android {
    namespace = "com.voicestudio.reader"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig {
        applicationId = "sh.voicestudio.reader"
        minSdk = 29
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = major.toInt() * 1000000 + minor.toInt() * 1000 + patch.toInt()
        versionName = appVersion
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The same native runtimes the main app builds; this app only adds the model catalogue.
    sourceSets["main"].assets.srcDir(rootDir.resolve("../native/android/build/assets"))
    sourceSets["main"].jniLibs.srcDir(rootDir.resolve("../native/android/build/jniLibs"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/catalog"))
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
val bundleCatalog by tasks.registering(Copy::class) {
    from(repository.resolve("mobile/contract/models.json"))
    from(repository.resolve("LICENSE")) { into("licenses"); rename { "VoiceStudio-AGPL-3.0.txt" } }
    into(layout.buildDirectory.dir("generated/catalog"))
}
tasks.named("preBuild") { dependsOn(bundleCatalog, ":app:buildNativeRuntimes") }
dependencies {
    implementation(files(rootDir.resolve("../native/android/build/downloads/sherpa.aar")).builtBy(":core:local:fetchRuntimes"))
    implementation(project(":core:local"))
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.core)
    implementation(libs.coroutines)
    implementation(libs.serialization)
    implementation(libs.media)
    implementation(libs.media.session)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    testImplementation(libs.junit)
}
