package ru.voboost.ota

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallerContractTest {
    @Test
    fun untrustedDefaultApkHandlerIsNeverAccepted() {
        val candidate =
            AppInstallerIdentity(
                packageName = "example.untrusted",
                activityName = "example.untrusted.Install",
                applicationFlags = ApplicationInfo.FLAG_SYSTEM,
                activityExported = true,
                enabled = true,
                platformSignatureMatch = true,
                uid = 1000,
            )

        assertFalse(candidate.isTrustedSystemInstaller())
    }

    @Test
    fun exactPlatformInstallerContractIsAccepted() {
        val candidate =
            AppInstallerIdentity(
                packageName = ApkInstallerContract.TRUSTED_PACKAGE,
                activityName = ApkInstallerContract.TRUSTED_ACTIVITY,
                applicationFlags = ApplicationInfo.FLAG_SYSTEM,
                activityExported = true,
                enabled = true,
                platformSignatureMatch = true,
                uid = 1000,
            )

        assertTrue(candidate.isTrustedSystemInstaller())
    }
}
