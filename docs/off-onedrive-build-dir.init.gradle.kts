// Redirects every module's build directory outside the synced OneDrive tree.
//
// The repo lives inside OneDrive. Files-On-Demand dehydrates generated files
// into read-only cloud placeholders (reparse tag 0x9000e01a), after which AGP
// fails with AccessDeniedException in generateDebugBuildConfig or "Unable to
// delete directory" in packageDebug. Building into <home>/lastwave-build instead
// of build/ removes that failure on every machine, without changing any path in
// the project files.
//
//   gradlew.bat -I docs/off-onedrive-build-dir.init.gradle.kts assembleDebug
//
// Override the location with LASTWAVE_BUILD_DIR (environment) or
// -Dlastwave.buildDir=... inside GRADLE_OPTS.
val outRoot = java.io.File(
    System.getenv("LASTWAVE_BUILD_DIR")
        ?: System.getProperty("lastwave.buildDir")
        ?: java.io.File(System.getProperty("user.home"), "lastwave-build").path,
)

gradle.beforeProject {
    val rel = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
    layout.buildDirectory.set(java.io.File(outRoot, rel))
}
