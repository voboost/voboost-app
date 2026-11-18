package ru.voboost.ota

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

internal interface PackageUpdater {
    fun request(
        verifiedApk: File,
        artifact: ProductArtifact,
    ): Boolean
}

internal class AndroidPackageUpdater(
    context: Context,
    private val fileOps: ApkShareFileOps = PlatformApkShareFileOps,
    private val gateway: AppInstallerGateway = AndroidAppInstallerGateway(context.applicationContext),
    private val shareDirectory: File = File(context.dataDir, "staging-apk"),
) : PackageUpdater {
    override fun request(
        verifiedApk: File,
        artifact: ProductArtifact,
    ): Boolean {
        require(artifact.file == "voboost-app.apk" && verifiedApk.isFile)
        require(verifiedApk.length() == artifact.size && sha256(verifiedApk) == artifact.sha256)
        val expectedUid = fileOps.effectiveUid()
        val expectedGid = fileOps.effectiveGid()
        val temporary = ".voboost-update.${token()}.tmp"
        var issued: Uri? = null
        try {
            fileOps.openDirectory(shareDirectory).use { directory ->
                val directoryIdentity = directory.identity()
                require(directoryIdentity.isOwnedDirectory(expectedUid, expectedGid))
                require(fileOps.lstat(shareDirectory)?.sameObject(directoryIdentity) == true)
                require(directory.listNames().all { it == TARGET || TEMP.matches(it) })
                directory.lstat(TARGET)?.let { existing ->
                    require(existing.isOwnedReadOnlyApk(expectedUid, expectedGid, existing.size))
                    directory.remove(TARGET)
                    directory.sync()
                }
                val output = directory.createExclusive(temporary) ?: error("APK share temp exists")
                val copyIdentity = output.identity()
                var copyName = temporary
                try {
                    output.use { authority ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        verifiedApk.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= artifact.size) { "APK copy exceeds signed size" }
                                digest.update(buffer, 0, count)
                                authority.output.write(buffer, 0, count)
                            }
                        }
                        require(
                            total == artifact.size && hex(digest.digest()) == artifact.sha256,
                        ) { "APK copy differs from signed artifact" }
                        authority.syncAndMakeReadOnly()
                        require(authority.identity().isOwnedReadOnlyApk(expectedUid, expectedGid, artifact.size))
                        directory.rename(temporary, TARGET)
                        copyName = TARGET
                        directory.sync()
                    }
                    directory.openRead(TARGET).use { read ->
                        require(read.identity().isOwnedReadOnlyApk(expectedUid, expectedGid, artifact.size))
                        val probe =
                            Intent(INSTALL_ACTION).apply {
                                setDataAndType(PROBE_URI, APK_MIME)
                                setPackage(ApkInstallerContract.TRUSTED_PACKAGE)
                            }
                        val installer = gateway.resolveCandidate(probe)
                        require(installer?.isTrustedSystemInstaller() == true)
                        require(sha256(read, artifact.size) == artifact.sha256) { "Retained APK differs from signed artifact" }
                        require(directory.lstat(TARGET)?.sameObject(read.identity()) == true) { "APK copy pathname changed" }
                        val uri = gateway.issueReadGrant(read, installer.uid, artifact.size)
                        issued = uri
                        require(DescriptorBoundApkProvider.isCanonicalGrantUri(uri))
                        directory.remove(TARGET)
                        directory.sync()
                        gateway.startActivity(
                            Intent(INSTALL_ACTION).apply {
                                setDataAndType(uri, APK_MIME)
                                component = ComponentName(ApkInstallerContract.TRUSTED_PACKAGE, ApkInstallerContract.TRUSTED_ACTIVITY)
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                            },
                        )
                        issued = null
                    }
                } finally {
                    val remaining = directory.lstat(copyName)
                    if (remaining != null && copyIdentity.sameObject(remaining)) {
                        directory.remove(copyName)
                        directory.sync()
                    }
                }
            }
            return true
        } catch (error: Exception) {
            issued?.let { runCatching { gateway.revokeReadGrant(it) } }
            throw error
        }
    }

    private fun token(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(
        read: ApkShareReadAuthority,
        expectedSize: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val identity = read.identity()
        ParcelFileDescriptor.AutoCloseInputStream(read.duplicateForGrant()).use { input ->
            input.channel.position(0)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= expectedSize) { "Retained APK exceeds signed size" }
                digest.update(buffer, 0, count)
            }
            require(total == expectedSize && read.identity() == identity) { "Retained APK changed" }
            input.channel.position(0)
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private const val TARGET = "voboost-update.apk"
        private const val INSTALL_ACTION = "android.intent.action.INSTALL_PACKAGE"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private val PROBE_URI = Uri.parse("content://${DescriptorBoundApkProvider.AUTHORITY}/probe")
        private val TEMP = Regex("^\\.voboost-update\\.[0-9a-f]{32}\\.tmp$")
        private val random = SecureRandom()
    }
}
