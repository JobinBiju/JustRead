plugins {
    base
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// Release APKs (one per ABI plus a universal one) for testing on a phone, copied to dist/
val releaseApk = tasks.register<Copy>("releaseApk") {
    dependsOn(":app:assembleRelease")
    from(project(":app").layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(layout.projectDirectory.dir("dist"))
    // app-arm64-v8a-release.apk -> JustRead-arm64-v8a-release.apk
    rename { it.replace(Regex("^app-"), "JustRead-") }
}
tasks.named("assemble") { dependsOn(releaseApk) }
