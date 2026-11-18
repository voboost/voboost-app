package ru.voboost.ota

/** Fixed Android PackageInstaller boundary for signed product updates. */
internal object ApkInstallerContract {
    const val TRUSTED_PACKAGE = "com.android.packageinstaller"
    const val TRUSTED_ACTIVITY = "com.android.packageinstaller.InstallStart"
    const val MAX_APK_BYTES = 1024L * 1024L * 1024L
}
