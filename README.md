# APK-MultiUpdate

Drop-in self-update solution for Android apps. Check GitHub releases, download updates, and install via multiple methods: Native, Session, Root, Shizuku, Dhizuku. No UI included - build your own.

> **Note:** This can serve as a workaround for FOSS apps affected by Google Play's upcoming developer verification requirements. The Shizuku, Dhizuku, and Root installation methods bypass standard installer restrictions.

## Features

- **Update Checker** - Fetch latest release from GitHub (or modify `Updater.kt` for your own source)
- **Downloader** - Download APK with progress tracking
- **5 Install Methods** - Native, Session (system apps only), Root, Shizuku, Dhizuku
- **Permission Helpers** - Handle Android 8+ install permissions

## Quick Start

### 1. Add to `gradle/libs.versions.toml`:
```toml
[versions]
shizuku = "13.1.5"
dhizuku = "2.5.4"
libsu = "6.0.0"
rikkaTools = "4.4.0"
rikkaHiddenAPI = "4.4.0"
hiddenapibypass = "6.1"

[libraries]
shizuku-api = { module = "dev.rikka.shizuku:api", version.ref = "shizuku" }
shizuku-provider = { module = "dev.rikka.shizuku:provider", version.ref = "shizuku" }
libsu-core = { module = "com.github.topjohnwu.libsu:core", version.ref = "libsu" }
dhizuku-api = { module = "io.github.iamr0s:Dhizuku-API", version.ref = "dhizuku" }
rikka-tools-refine-runtime = { module = "dev.rikka.tools.refine:runtime", version.ref = "rikkaTools" }
rikka-hidden-stub = { module = "dev.rikka.hidden:stub", version.ref = "rikkaHiddenAPI" }
lsposed-hiddenapibypass = { module = "org.lsposed.hiddenapibypass:hiddenapibypass", version.ref = "hiddenapibypass" }

[plugins]
rikka-tools-refine = { id = "dev.rikka.tools.refine", version.ref = "rikkaTools" }
```

### 2. Add to `app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.rikka.tools.refine)
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.dhizuku.api)
    implementation(libs.libsu.core)
    implementation(libs.rikka.tools.refine.runtime)
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.lsposed.hiddenapibypass)
}
```

### 3. Copy the 5 source files to your project:
```
src/
├── Updater.kt           # Check GitHub for updates
├── ApkDownloader.kt     # Download APK with progress
├── AppInstaller.kt      # 5 install methods
├── InstallReceiver.kt   # Broadcast receiver for install status
└── Installer.kt         # InstallerType enum
```

Then change the package in all 5 files:
```kotlin
// Change:
package com.example.installer
// To:
package com.yourapp.yourpackage
```

### 4. Update `InstallReceiver.kt` line 12:
```kotlin
// Change:
const val ACTION_INSTALL_STATUS = "com.example.app.INSTALL_STATUS"
// To:
const val ACTION_INSTALL_STATUS = "com.yourapp.yourpackage.INSTALL_STATUS"
```

### 5. Add to `AndroidManifest.xml`:
```xml
<manifest>
    <!-- Outside application tag -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-sdk tools:overrideLibrary="rikka.shizuku.api, rikka.shizuku.provider, rikka.shizuku.shared, rikka.shizuku.aidl, com.rosan.dhizuku.api" />

    <!-- testOnly required for Dhizuku -->
    <application android:testOnly="true">

        <!-- FileProvider for APK files (skip if you already have one) -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.FileProvider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/provider_paths" />
        </provider>

        <!-- Install callback receiver -->
        <receiver
            android:name=".InstallReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="${applicationId}.INSTALL_STATUS" />
            </intent-filter>
        </receiver>

        <!-- Shizuku provider (required for Shizuku install method) -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:enabled="true"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

    </application>
</manifest>
```

**Note:** If your app already has a FileProvider with a different authority (e.g., `.provider` instead of `.FileProvider`), update `AppInstaller.kt` line 134 to match:
```kotlin
// Change:
"${context.packageName}.FileProvider"
// To match your existing authority, e.g.:
"${context.packageName}.provider"
```

### 6. Copy `config/provider_paths.xml` to `app/src/main/res/xml/provider_paths.xml`

### 7. Add to your Application class:
```kotlin
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("I", "L")
        }
    }
}
```

### 8. Add ProGuard rules from `config/proguard-rules.pro`

---

## Usage

```kotlin
// 1. Init updater once (e.g., in Application.onCreate)
Updater.init(
    repo = "YourOrg/YourApp",
    currentVersion = BuildConfig.VERSION_NAME
)

// 2. Check for updates
val (release, hasUpdate) = Updater.checkForUpdate().getOrThrow()
if (!hasUpdate) return

// 3. Check install permission (Android 8+)
if (!ApkDownloader.canInstallPackages(context)) {
    // Request permission
    startActivity(ApkDownloader.getInstallPermissionIntent(context))
    return
}

// 4. Download and install
ApkDownloader.downloadApk(context, release.downloadUrl).collect { state ->
    when (state) {
        is DownloadState.Downloading -> {
            // state.progress (0.0-1.0)
            // state.downloadedBytes, state.totalBytes
        }
        is DownloadState.Completed -> {
            val result = AppInstaller.install(context, state.file, InstallerType.SESSION)
            when (result) {
                is InstallResult.Success -> { /* Silent install done */ }
                is InstallResult.RequiresUserAction -> { /* System UI shown */ }
                is InstallResult.Error -> { /* result.message */ }
            }
        }
        is DownloadState.Error -> { /* state.message */ }
        else -> {}
    }
}
```

---

## API Reference

### Updater
Uses GitHub Releases API. Modify `Updater.kt` for other sources (your server, F-Droid, etc).
```kotlin
Updater.init(repo, currentVersion, assetName?)  // Initialize
Updater.checkForUpdate(forceRefresh?): Result<Pair<ReleaseInfo, Boolean>>
```

### ApkDownloader
```kotlin
ApkDownloader.canInstallPackages(context): Boolean      // Check permission
ApkDownloader.getInstallPermissionIntent(context): Intent  // Open settings
ApkDownloader.downloadApk(context, url): Flow<DownloadState>
ApkDownloader.getDownloadedApk(context): File?          // Get cached APK
ApkDownloader.clearDownloadedApk(context)               // Delete cached APK
```

### AppInstaller
```kotlin
AppInstaller.install(context, file, type): InstallResult

// Availability checks:
AppInstaller.hasRootAccess(): Boolean           // Note: triggers root prompt
AppInstaller.hasShizukuOrSui(context): Boolean  // Check if Shizuku app installed
AppInstaller.isShizukuAlive(): Boolean          // Check if Shizuku service running
AppInstaller.hasShizukuPermission(): Boolean    // Check if permission granted
AppInstaller.hasDhizuku(context): Boolean       // Check if Dhizuku app installed
AppInstaller.hasDhizukuPermission(context): Boolean  // Initializes Dhizuku internally
```

Note: `hasRootAccess()` calls `Shell.getShell().isRoot` which triggers a root permission prompt via Magisk/SuperSU. Do not call it just to check whether to show the Root option in your UI. Instead, show all installer options and call `hasRootAccess()` only when the user selects Root - the grant dialog will appear at that point.

### Requesting Permissions

Before calling `AppInstaller.install()` with SHIZUKU or DHIZUKU, you must check and request permission first. The permission check functions initialize the underlying services, which is required before installation can proceed.

**Root:**
```kotlin
// When user selects Root, check on IO dispatcher - this shows the root grant dialog
fun onRootSelected(apkFile: File) {
    coroutineScope.launch {
        val hasRoot = withContext(Dispatchers.IO) {
            AppInstaller.hasRootAccess()
        }
        if (hasRoot) {
            AppInstaller.install(context, apkFile, InstallerType.ROOT)
        } else {
            // User denied root access
        }
    }
}
```

**Shizuku:**
```kotlin
// Store pending state for after permission callback
var pendingInstall: File? = null

// Register listener in onCreate
private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
    if (grantResult == PackageManager.PERMISSION_GRANTED) {
        pendingInstall?.let { file ->
            // Now safe to install with SHIZUKU
            AppInstaller.install(context, file, InstallerType.SHIZUKU)
        }
    }
    pendingInstall = null
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Shizuku.addRequestPermissionResultListener(shizukuListener)
}

override fun onDestroy() {
    super.onDestroy()
    Shizuku.removeRequestPermissionResultListener(shizukuListener)
}

// When user selects Shizuku:
fun onShizukuSelected(apkFile: File) {
    if (!AppInstaller.hasShizukuOrSui(context)) {
        // Shizuku not installed
        return
    }
    if (!AppInstaller.isShizukuAlive()) {
        // Shizuku not running
        return
    }
    if (AppInstaller.hasShizukuPermission()) {
        // Already have permission, install directly
        AppInstaller.install(context, apkFile, InstallerType.SHIZUKU)
    } else {
        // Request permission, install in callback
        pendingInstall = apkFile
        Shizuku.requestPermission(0)
    }
}
```

**Dhizuku:**
```kotlin
var pendingInstall: File? = null

fun onDhizukuSelected(apkFile: File) {
    if (!AppInstaller.hasDhizuku(context)) {
        // Dhizuku not installed
        return
    }
    try {
        if (!Dhizuku.init(context)) {
            // Dhizuku init failed
            return
        }
        if (Dhizuku.isPermissionGranted()) {
            // Already have permission, install directly
            AppInstaller.install(context, apkFile, InstallerType.DHIZUKU)
        } else {
            // Request permission
            pendingInstall = apkFile
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        pendingInstall?.let { file ->
                            AppInstaller.install(context, file, InstallerType.DHIZUKU)
                        }
                    }
                    pendingInstall = null
                }
            })
        }
    } catch (e: Exception) {
        // Dhizuku not available or not properly set up
    }
}
```

Note: Dhizuku may crash with `SQLiteConstraintException: UNIQUE constraint failed: app.uid` when requesting permission. This is a known bug in the Dhizuku app itself. The permission is still granted despite the crash, and installation will work on subsequent attempts.

---

## Install Methods

| Type | Description | Requirements |
|------|-------------|--------------|
| `NATIVE` | Opens system installer | None |
| `SESSION` | PackageInstaller API | System app for silent install |
| `ROOT` | Silent install | Rooted device |
| `SHIZUKU` | Silent install | Shizuku running + permission |
| `DHIZUKU` | Silent install | Dhizuku as device owner |

---

## Example

See the [Metrolist implementation PR](https://github.com/MetrolistGroup/Metrolist/pull/3147) for a complete working example, from my fork's [feat/download-install-updates branch](https://github.com/alltechdev/Metrolist-fix/tree/feat/download-install-updates).

---

## Credits

- [Aurora Store](https://github.com/whyorean/AuroraStore) - Installer implementation
- Dhizuku support adapted from [aurora-dhizuku](https://github.com/alltechdev/aurora-dhizuku)

## License

GPL-3.0
