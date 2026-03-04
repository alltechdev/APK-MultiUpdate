# Shizuku & Hidden API Rules
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class dev.rikka.tools.refine.** { *; }

# Hidden Android APIs accessed via Shizuku
-keep class android.content.pm.IPackageManager { *; }
-keep class android.content.pm.IPackageManager$Stub { *; }
-keep class android.content.pm.IPackageInstaller { *; }
-keep class android.content.pm.IPackageInstaller$Stub { *; }
-keep class android.content.pm.IPackageInstallerSession { *; }
-keep class android.content.pm.IPackageInstallerSession$Stub { *; }
-keep class android.content.pm.PackageInstallerHidden { *; }
-keep class android.content.pm.PackageInstallerHidden$* { *; }
-keep class android.content.pm.PackageManagerHidden { *; }

# libsu for root access
-keep class com.topjohnwu.superuser.** { *; }

# Dhizuku for device owner installation
-keep class com.rosan.dhizuku.** { *; }
