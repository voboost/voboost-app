package ru.voboost.ota

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PackageUpdaterTest {
    @Test
    fun verifiedAppArtifactUsesExactInstallerAndUnlinksSharePath() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("voboost-package-update").toFile()
        val source = File(root, "voboost-app.apk").apply { writeText("signed-apk") }
        val artifact =
            ProductArtifact(
                "app",
                "voboost-app.apk",
                source.length(),
                OtaHttpTransport.sha256(source.readBytes()),
                "package-installer",
                "ru.voboost",
                1,
                "a".repeat(64),
                null,
            )
        val gateway = RecordingInstallerGateway()
        val share = File(root, "share")

        assertTrue(AndroidPackageUpdater(context, NioApkShareFileOps, gateway, share).request(source, artifact))
        assertEquals(ApkInstallerContract.TRUSTED_PACKAGE, gateway.started?.component?.packageName)
        assertEquals(ApkInstallerContract.TRUSTED_ACTIVITY, gateway.started?.component?.className)
        assertTrue(share.listFiles().orEmpty().isEmpty())
        gateway.uri?.let(DescriptorBoundApkProvider::revoke)
    }

    private class RecordingInstallerGateway : AppInstallerGateway {
        var uri: Uri? = null
        var started: Intent? = null

        override fun resolveCandidate(probe: Intent): AppInstallerIdentity =
            AppInstallerIdentity(
                ApkInstallerContract.TRUSTED_PACKAGE,
                ApkInstallerContract.TRUSTED_ACTIVITY,
                ApplicationInfo.FLAG_SYSTEM,
                activityExported = true,
                enabled = true,
                platformSignatureMatch = true,
                uid = 1000,
            )

        override fun issueReadGrant(
            readAuthority: ApkShareReadAuthority,
            expectedCallerUid: Int,
            expectedSize: Long,
        ): Uri = DescriptorBoundApkProvider.issue(readAuthority.duplicateForGrant(), expectedCallerUid, expectedSize).also { uri = it }

        override fun revokeReadGrant(uri: Uri) = DescriptorBoundApkProvider.revoke(uri)

        override fun startActivity(intent: Intent) {
            started = intent
        }
    }
}
