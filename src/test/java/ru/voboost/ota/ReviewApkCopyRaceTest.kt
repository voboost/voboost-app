package ru.voboost.ota

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReviewApkCopyRaceTest {
    @Test
    fun packageInstallerCannotReceiveBytesSubstitutedAfterSourceVerification() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("voboost-copy-race").toFile()
        val source = File(root, "voboost-app.apk").apply { writeText("signed-apk") }
        val expected = source.readBytes()
        val artifact =
            ProductArtifact(
                "app", "voboost-app.apk", source.length(),
                OtaHttpTransport.sha256(expected), "package-installer", "ru.voboost", 1,
                "a".repeat(64), null,
            )
        val ops =
            object : ApkShareFileOps by NioApkShareFileOps {
                override fun effectiveUid(): Int {
                    source.writeText("other--apk")
                    return NioApkShareFileOps.effectiveUid()
                }
            }
        var delivered = byteArrayOf()
        val gateway =
            object : AppInstallerGateway {
                override fun resolveCandidate(probe: Intent) =
                    AppInstallerIdentity(
                        ApkInstallerContract.TRUSTED_PACKAGE,
                        ApkInstallerContract.TRUSTED_ACTIVITY,
                        ApplicationInfo.FLAG_SYSTEM,
                        true,
                        true,
                        true,
                        1000,
                    )

                override fun issueReadGrant(
                    readAuthority: ApkShareReadAuthority,
                    expectedCallerUid: Int,
                    expectedSize: Long,
                ): Uri {
                    readAuthority.duplicateForGrant().use { descriptor ->
                        delivered = FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                    }
                    return Uri.parse("content://${DescriptorBoundApkProvider.AUTHORITY}/grant/${"a".repeat(32)}.apk")
                }

                override fun revokeReadGrant(uri: Uri) {}

                override fun startActivity(intent: Intent) {}
            }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidPackageUpdater(context, ops, gateway, File(root, "share")).request(source, artifact)
        }
        assertEquals(0, delivered.size)
        assertEquals(emptyList<String>(), File(root, "share").listFiles().orEmpty().map { it.name })
        root.deleteRecursively()
    }

    @Test
    fun verifiedDescriptorIsRewoundAndOnlyExactCopiedBytesReachInstaller() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("voboost-copy-positive").toFile()
        val source = File(root, "voboost-app.apk").apply { writeText("signed-apk") }
        val bytes = source.readBytes()
        val artifact =
            ProductArtifact(
                "app", "voboost-app.apk", source.length(), OtaHttpTransport.sha256(bytes),
                "package-installer", "ru.voboost", 1, "a".repeat(64), null,
            )
        var received = byteArrayOf()
        var starts = 0
        val gateway =
            object : AppInstallerGateway {
                override fun resolveCandidate(probe: Intent) =
                    AppInstallerIdentity(
                        ApkInstallerContract.TRUSTED_PACKAGE,
                        ApkInstallerContract.TRUSTED_ACTIVITY,
                        ApplicationInfo.FLAG_SYSTEM,
                        true,
                        true,
                        true,
                        1000,
                    )

                override fun issueReadGrant(
                    readAuthority: ApkShareReadAuthority,
                    expectedCallerUid: Int,
                    expectedSize: Long,
                ): Uri {
                    val descriptor = readAuthority.duplicateForGrant()
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { received = it.readBytes() }
                    return Uri.parse("content://${DescriptorBoundApkProvider.AUTHORITY}/grant/${"a".repeat(32)}.apk")
                }

                override fun revokeReadGrant(uri: Uri) {}

                override fun startActivity(intent: Intent) {
                    starts++
                }
            }
        val updater = AndroidPackageUpdater(context, NioApkShareFileOps, gateway, File(root, "share"))
        assertEquals(true, updater.request(source, artifact))
        org.junit.Assert.assertArrayEquals(bytes, received)
        assertEquals(1, starts)
        assertEquals(emptyList<String>(), File(root, "share").listFiles().orEmpty().map { it.name })
        root.deleteRecursively()
    }

    @Test
    fun copiedFileChangedDuringInstallerResolutionCannotReceiveAGrant() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("voboost-output-race").toFile()
        val source = File(root, "voboost-app.apk").apply { writeText("signed-apk") }
        val share = File(root, "share")
        val artifact =
            ProductArtifact(
                "app", "voboost-app.apk", source.length(), OtaHttpTransport.sha256(source.readBytes()),
                "package-installer", "ru.voboost", 1, "a".repeat(64), null,
            )
        var grants = 0
        val gateway =
            object : AppInstallerGateway {
                override fun resolveCandidate(probe: Intent): AppInstallerIdentity {
                    share.listFiles().orEmpty().single().apply {
                        check(setWritable(true, true))
                        writeText("other--apk")
                        check(setWritable(false, false))
                    }
                    return AppInstallerIdentity(
                        ApkInstallerContract.TRUSTED_PACKAGE,
                        ApkInstallerContract.TRUSTED_ACTIVITY,
                        ApplicationInfo.FLAG_SYSTEM,
                        true,
                        true,
                        true,
                        1000,
                    )
                }

                override fun issueReadGrant(
                    readAuthority: ApkShareReadAuthority,
                    expectedCallerUid: Int,
                    expectedSize: Long,
                ): Uri {
                    grants++
                    error("a substituted copy must never reach grant issuance")
                }

                override fun revokeReadGrant(uri: Uri) {}

                override fun startActivity(intent: Intent) {
                    error("a substituted copy must never reach installation")
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidPackageUpdater(context, NioApkShareFileOps, gateway, share).request(source, artifact)
        }
        assertEquals(0, grants)
        assertEquals(emptyList<String>(), share.listFiles().orEmpty().map { it.name })
        root.deleteRecursively()
    }
}
