package com.example.installer

enum class InstallerType {
    NATIVE,   // Standard system installer
    SESSION,  // PackageInstaller API (silent only for system apps)
    ROOT,     // Silent via root
    SHIZUKU,  // Silent via Shizuku
    DHIZUKU   // Silent via Dhizuku device owner
}
