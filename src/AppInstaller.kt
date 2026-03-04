package com.example.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider

import com.rosan.dhizuku.api.Dhizuku
import com.topjohnwu.superuser.Shell
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern

sealed class InstallResult {
    data object Success : InstallResult()
    data class Error(val message: String) : InstallResult()
    data object RequiresUserAction : InstallResult()
}

object AppInstaller {
    private const val TAG = "AppInstaller"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val DHIZUKU_PACKAGE = "com.rosan.dhizuku"
    private const val PLAY_PACKAGE_NAME = "com.android.vending"

    // Extension functions for Shizuku binder wrapping
    private fun IBinder.wrap() = ShizukuBinderWrapper(this)
    private fun IInterface.asShizukuBinder() = this.asBinder().wrap()

    // Extension functions for Dhizuku binder wrapping
    private fun IBinder.wrapDhizuku(): IBinder = Dhizuku.binderWrapper(this)
    private fun IInterface.asDhizukuBinder(): IBinder = Dhizuku.binderWrapper(this.asBinder())

    // Cached Dhizuku binders for performance
    private var cachedDhizukuIPackageInstaller: IPackageInstaller? = null
    private var cachedDhizukuPackageInstaller: PackageInstaller? = null

    fun hasRootAccess(): Boolean = Shell.getShell().isRoot

    fun hasShizukuOrSui(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasDhizuku(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.packageManager.getPackageInfo(DHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasDhizukuPermission(context: Context): Boolean {
        return try {
            Dhizuku.init(context)
            Dhizuku.isPermissionGranted()
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureDhizukuInit(context: Context): Boolean {
        return try {
            Dhizuku.init(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Dhizuku", e)
            // Clear cache on init failure
            cachedDhizukuIPackageInstaller = null
            cachedDhizukuPackageInstaller = null
            false
        }
    }

    suspend fun install(
        context: Context,
        apkFile: File,
        installerType: InstallerType
    ): InstallResult = withContext(Dispatchers.IO) {
        when (installerType) {
            InstallerType.NATIVE -> installNative(context, apkFile)
            InstallerType.SESSION -> installSession(context, apkFile)
            InstallerType.ROOT -> installRoot(context, apkFile)
            InstallerType.SHIZUKU -> installShizuku(context, apkFile)
            InstallerType.DHIZUKU -> installDhizuku(context, apkFile)
        }
    }

    private fun installNative(context: Context, apkFile: File): InstallResult {
        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            }

            context.startActivity(installIntent)
            InstallResult.RequiresUserAction
        } catch (e: Exception) {
            InstallResult.Error(e.message ?: "Failed to launch installer")
        }
    }

    private fun installSession(context: Context, apkFile: File): InstallResult {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use {
                val outputStream = it.openWrite("update.apk", 0, apkFile.length())
                FileInputStream(apkFile).use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.close()

                val intent = Intent(context, InstallReceiver::class.java).apply {
                    action = InstallReceiver.ACTION_INSTALL_STATUS
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                it.commit(pendingIntent.intentSender)
            }

            InstallResult.RequiresUserAction
        } catch (e: Exception) {
            InstallResult.Error(e.message ?: "Session install failed")
        }
    }

    /**
     * Root installation using libsu - based on Aurora Store's RootInstaller
     */
    private fun installRoot(context: Context, apkFile: File): InstallResult {
        if (!Shell.getShell().isRoot) {
            return InstallResult.Error("Root access not available")
        }

        return try {
            val totalSize = apkFile.length()

            // Create install session via pm
            val createResult = Shell.cmd(
                "pm install-create -i $PLAY_PACKAGE_NAME --user 0 -r -S $totalSize"
            ).exec()

            if (!createResult.isSuccess) {
                return InstallResult.Error(createResult.err.joinToString("\n").ifEmpty { "Failed to create install session" })
            }

            val response = createResult.out
            val sessionIdPattern = Pattern.compile("(\\d+)")
            val sessionIdMatcher = sessionIdPattern.matcher(response.firstOrNull() ?: "")

            if (!sessionIdMatcher.find()) {
                return InstallResult.Error("Failed to get session ID")
            }

            val sessionId = sessionIdMatcher.group(1)?.toInt()
                ?: return InstallResult.Error("Invalid session ID")

            // Write APK to session
            val writeResult = Shell.cmd(
                "cat \"${apkFile.absolutePath}\" | pm install-write -S ${apkFile.length()} $sessionId \"${apkFile.name}\""
            ).exec()

            if (!writeResult.isSuccess) {
                return InstallResult.Error(writeResult.err.joinToString("\n").ifEmpty { "Failed to write APK" })
            }

            // Commit session
            val commitResult = Shell.cmd("pm install-commit $sessionId").exec()

            if (commitResult.isSuccess) {
                InstallResult.Success
            } else {
                InstallResult.Error(commitResult.err.joinToString("\n").ifEmpty { "Install commit failed" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root install failed", e)
            InstallResult.Error(e.message ?: "Root install failed")
        }
    }

    /**
     * Shizuku installation - based on Aurora Store's ShizukuInstaller
     * Uses hidden APIs via rikka.tools.refine
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun installShizuku(context: Context, apkFile: File): InstallResult {
        if (!isShizukuAlive()) {
            return InstallResult.Error("Shizuku is not running")
        }
        if (!hasShizukuPermission()) {
            return InstallResult.Error("Shizuku permission required")
        }

        return try {
            val iPackageManager = IPackageManager.Stub.asInterface(
                SystemServiceHelper.getSystemService("package").wrap()
            )

            // Get package installer via Shizuku
            val iPackageInstaller = IPackageInstaller.Stub.asInterface(
                iPackageManager.packageInstaller.asShizukuBinder()
            )

            // Create PackageInstaller instance
            val packageInstaller = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Refine.unsafeCast<PackageInstaller>(
                    PackageInstallerHidden(iPackageInstaller, PLAY_PACKAGE_NAME, null, 0)
                )
            } else {
                Refine.unsafeCast<PackageInstaller>(
                    PackageInstallerHidden(iPackageInstaller, PLAY_PACKAGE_NAME, 0)
                )
            }

            // Create session params with replace flag
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            var flags = Refine.unsafeCast<PackageInstallerHidden.SessionParamsHidden>(params).installFlags
            flags = flags or PackageManagerHidden.INSTALL_REPLACE_EXISTING
            Refine.unsafeCast<PackageInstallerHidden.SessionParamsHidden>(params).installFlags = flags

            // Create and open session
            val sessionId = packageInstaller.createSession(params)
            val iSession = IPackageInstallerSession.Stub.asInterface(
                iPackageInstaller.openSession(sessionId).asShizukuBinder()
            )
            val session = Refine.unsafeCast<PackageInstaller.Session>(
                PackageInstallerHidden.SessionHidden(iSession)
            )

            // Write APK to session
            apkFile.inputStream().use { input ->
                session.openWrite("update_${System.currentTimeMillis()}", 0, -1).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            // Create callback intent
            val callBackIntent = Intent(context, InstallReceiver::class.java).apply {
                action = InstallReceiver.ACTION_INSTALL_STATUS
                setPackage(context.packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callBackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            // Commit session
            session.commit(pendingIntent.intentSender)
            session.close()

            InstallResult.RequiresUserAction
        } catch (e: NoSuchMethodError) {
            // Hidden API changed in Android 16+
            Log.e(TAG, "Shizuku install failed - incompatible Android version", e)
            InstallResult.Error("Shizuku installer not supported on this Android version")
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku install failed", e)
            InstallResult.Error(e.message ?: "Shizuku install failed")
        }
    }

    /**
     * Dhizuku installation - based on Aurora Store's DhizukuInstaller
     * Uses Dhizuku API for device owner based installation
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun installDhizuku(context: Context, apkFile: File): InstallResult {
        // Ensure Dhizuku is initialized
        if (!ensureDhizukuInit(context)) {
            return InstallResult.Error("Dhizuku is not available or permission not granted")
        }

        if (!Dhizuku.isPermissionGranted()) {
            return InstallResult.Error("Dhizuku is not available or permission not granted")
        }

        return try {
            // Get IPackageInstaller via Dhizuku
            val iPackageInstaller = cachedDhizukuIPackageInstaller ?: run {
                val iPackageManager = IPackageManager.Stub.asInterface(
                    SystemServiceHelper.getSystemService("package").wrapDhizuku()
                )
                IPackageInstaller.Stub.asInterface(
                    iPackageManager.packageInstaller.asDhizukuBinder()
                ).also { cachedDhizukuIPackageInstaller = it }
            }

            // Get PackageInstaller instance
            val packageInstaller = cachedDhizukuPackageInstaller ?: run {
                val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Refine.unsafeCast<PackageInstaller>(
                        PackageInstallerHidden(iPackageInstaller, DHIZUKU_PACKAGE, null, 0)
                    )
                } else {
                    Refine.unsafeCast<PackageInstaller>(
                        PackageInstallerHidden(iPackageInstaller, DHIZUKU_PACKAGE, 0)
                    )
                }
                installer.also { cachedDhizukuPackageInstaller = it }
            }

            // Create session params with replace flag
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            var flags = Refine.unsafeCast<PackageInstallerHidden.SessionParamsHidden>(params).installFlags
            flags = flags or PackageManagerHidden.INSTALL_REPLACE_EXISTING
            Refine.unsafeCast<PackageInstallerHidden.SessionParamsHidden>(params).installFlags = flags

            // Create and open session
            val sessionId = packageInstaller.createSession(params)
            val iSession = IPackageInstallerSession.Stub.asInterface(
                iPackageInstaller.openSession(sessionId).asDhizukuBinder()
            )
            val session = Refine.unsafeCast<PackageInstaller.Session>(
                PackageInstallerHidden.SessionHidden(iSession)
            )

            // Write APK to session
            apkFile.inputStream().use { input ->
                session.openWrite("update_${System.currentTimeMillis()}", 0, -1).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            // Create callback intent
            val callBackIntent = Intent(context, InstallReceiver::class.java).apply {
                action = InstallReceiver.ACTION_INSTALL_STATUS
                setPackage(context.packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callBackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            // Commit session
            session.commit(pendingIntent.intentSender)
            session.close()

            InstallResult.RequiresUserAction
        } catch (e: Exception) {
            Log.e(TAG, "Dhizuku install failed", e)
            // Clear cache on failure in case binder is stale
            cachedDhizukuIPackageInstaller = null
            cachedDhizukuPackageInstaller = null
            InstallResult.Error(e.message ?: "Dhizuku is not available or permission not granted")
        }
    }
}
