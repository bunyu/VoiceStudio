import groovy.json.JsonSlurper
plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
// A separate, focused app: open a book and listen in a Vietnamese voice. Its engine is VieNeu-TTS v3 Nano
// on ONNX Runtime; from VoiceStudio it reuses book import, audio decoding and AAC export (core:local).
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
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
// The Vietnamese phonemiser is Rust (native/g2p). It needs rustup with the Android targets and cargo-ndk:
//   rustup target add aarch64-linux-android x86_64-linux-android && cargo install cargo-ndk
val g2pSource = layout.projectDirectory.dir("native/g2p")
val buildG2p by tasks.registering(Exec::class) {
    inputs.files(g2pSource.file("Cargo.toml"), g2pSource.file("Cargo.lock"))
    inputs.dir(g2pSource.dir("src"))
    val out = layout.buildDirectory.dir("generated/jniLibs")
    outputs.dir(out)
    workingDir = g2pSource.asFile
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    val cargo = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "cargo.exe" else "cargo"
    commandLine(cargo, "ndk", "--platform", "29", "-t", "arm64-v8a", "-t", "x86_64", "-o", out.get().asFile.absolutePath, "build", "--release", "--locked")
}
val bundleLicences by tasks.registering(Copy::class) {
    from(repository.resolve("LICENSE")) { into("licenses"); rename { "VoiceStudio-AGPL-3.0.txt" } }
    into(layout.buildDirectory.dir("generated/catalog"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/catalog"))
tasks.named("preBuild") { dependsOn(bundleLicences, buildG2p) }
dependencies {
    implementation(project(":core:local"))
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.core)
    implementation(libs.coroutines)
    implementation(libs.serialization)
    implementation(libs.media)
    implementation(libs.media.session)
    implementation(libs.onnxruntime.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    testImplementation(libs.junit)
    // The same Java API as the Android runtime, so the engine port is tested off the phone.
    testImplementation(libs.onnxruntime.jvm)
}
