package ru.voboost.ota

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

internal data class AppInstallerIdentity(
    val packageName: String,
    val activityName: String,
    val applicationFlags: Int,
    val activityExported: Boolean,
    val enabled: Boolean,
    val platformSignatureMatch: Boolean,
    val uid: Int,
) {
    fun isTrustedSystemInstaller(): Boolean {
        val systemFlags =
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return packageName == ApkInstallerContract.TRUSTED_PACKAGE &&
            activityName == ApkInstallerContract.TRUSTED_ACTIVITY &&
            applicationFlags and systemFlags != 0 &&
            activityExported &&
            enabled &&
            platformSignatureMatch
    }
}

internal interface AppInstallerGateway {
    fun resolveCandidate(probe: Intent): AppInstallerIdentity?

    /**
     * Transfer a duplicate of the already verified descriptor to the one-shot
     * provider. Implementations must never reopen a pathname here.
     */
    fun issueReadGrant(
        readAuthority: ApkShareReadAuthority,
        expectedCallerUid: Int,
        expectedSize: Long,
    ): Uri

    fun revokeReadGrant(uri: Uri)

    fun startActivity(intent: Intent)
}

internal class AndroidAppInstallerGateway(private val context: Context) : AppInstallerGateway {
    override fun resolveCandidate(probe: Intent): AppInstallerIdentity? {
        val packageManager = context.packageManager
        val activity =
            packageManager.resolveActivity(probe, PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
                ?: return null
        val application = activity.applicationInfo ?: return null
        return AppInstallerIdentity(
            packageName = activity.packageName,
            activityName = activity.name,
            applicationFlags = application.flags,
            activityExported = activity.exported,
            enabled = activity.enabled && application.enabled,
            platformSignatureMatch =
                packageManager.checkSignatures(activity.packageName, PLATFORM_PACKAGE) ==
                    PackageManager.SIGNATURE_MATCH,
            uid = application.uid,
        )
    }

    override fun issueReadGrant(
        readAuthority: ApkShareReadAuthority,
        expectedCallerUid: Int,
        expectedSize: Long,
    ): Uri =
        DescriptorBoundApkProvider.issue(
            readAuthority.duplicateForGrant(),
            expectedCallerUid,
            expectedSize,
        )

    override fun revokeReadGrant(uri: Uri) = DescriptorBoundApkProvider.revoke(uri)

    override fun startActivity(intent: Intent) = context.startActivity(intent)

    private companion object {
        private const val PLATFORM_PACKAGE = "android"
    }
}

internal data class ApkShareFileIdentity(
    val device: Long,
    val inode: Long,
    val uid: Int,
    val gid: Int,
    val mode: Int,
    val linkCount: Long,
    val size: Long,
) {
    fun sameObject(other: ApkShareFileIdentity): Boolean = device == other.device && inode == other.inode

    fun isOwnedDirectory(
        expectedUid: Int,
        expectedGid: Int,
    ): Boolean =
        uid == expectedUid &&
            gid == expectedGid &&
            mode == (DIRECTORY_TYPE or DIRECTORY_OWNER_ONLY) &&
            linkCount >= 1L

    fun isOwnedReadOnlyApk(
        expectedUid: Int,
        expectedGid: Int,
        expectedSize: Long,
    ): Boolean =
        uid == expectedUid &&
            gid == expectedGid &&
            mode == (REGULAR_TYPE or FILE_OWNER_READ_ONLY) &&
            linkCount == 1L &&
            size == expectedSize
}

internal interface ApkShareWriteAuthority : java.io.Closeable {
    val output: OutputStream

    fun identity(): ApkShareFileIdentity

    fun syncAndMakeReadOnly()
}

internal interface ApkShareReadAuthority : java.io.Closeable {
    fun identity(): ApkShareFileIdentity

    /** Duplicate this exact opened inode for transfer to the content provider. */
    fun duplicateForGrant(): ParcelFileDescriptor
}

internal interface ApkShareDirectoryAuthority : java.io.Closeable {
    fun identity(): ApkShareFileIdentity

    fun listNames(): List<String>

    /**
     * The caller owns path-revalidation: before and after every [rename] and
     * [remove], compare [lstat] results against the identity previously
     * captured from the opened descriptor. This authority performs the raw
     * syscall only and does not itself detect substitution.
     */

    fun createExclusive(name: String): ApkShareWriteAuthority?

    fun openRead(name: String): ApkShareReadAuthority

    fun lstat(name: String): ApkShareFileIdentity?

    fun rename(
        sourceName: String,
        targetName: String,
    )

    fun remove(name: String)

    fun sync()
}

internal interface ApkShareFileOps {
    fun openDirectory(path: File): ApkShareDirectoryAuthority

    fun lstat(path: File): ApkShareFileIdentity?

    fun effectiveUid(): Int

    fun effectiveGid(): Int
}

internal object PlatformApkShareFileOps : ApkShareFileOps {
    override fun openDirectory(path: File): ApkShareDirectoryAuthority {
        val parent =
            path.parentFile ?: throw IllegalArgumentException("APK-share path has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create APK-share parent")
        }
        try {
            Os.mkdir(path.path, DIRECTORY_OWNER_ONLY)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST) throw error
        }
        val descriptor =
            Os.open(
                path.path,
                OsConstants.O_RDONLY or
                    OPEN_DIRECTORY_FLAG or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                0,
            )
        return try {
            PlatformApkShareDirectoryAuthority(ParcelFileDescriptor.dup(descriptor))
        } finally {
            Os.close(descriptor)
        }
    }

    override fun lstat(path: File): ApkShareFileIdentity? = platformLstat(path.path)

    override fun effectiveUid(): Int = Os.geteuid()

    override fun effectiveGid(): Int = Os.getegid()
}

private class PlatformApkShareDirectoryAuthority(
    private val descriptor: ParcelFileDescriptor,
) : ApkShareDirectoryAuthority {
    private val anchoredRoot = File("/proc/self/fd/${descriptor.fd}")

    override fun identity(): ApkShareFileIdentity = platformIdentity(Os.fstat(descriptor.fileDescriptor))

    override fun listNames(): List<String> =
        anchoredRoot.list()?.toList()
            ?: throw IllegalStateException("Failed to list retained APK-share directory")

    override fun createExclusive(name: String): ApkShareWriteAuthority? {
        val fileDescriptor =
            try {
                Os.open(
                    child(name).path,
                    OsConstants.O_WRONLY or
                        OsConstants.O_CREAT or
                        OsConstants.O_EXCL or
                        OsConstants.O_CLOEXEC or
                        OsConstants.O_NOFOLLOW,
                    FILE_OWNER_READ_WRITE,
                )
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST) return null
                throw error
            }
        return try {
            PlatformApkShareWriteAuthority(fileDescriptor)
        } catch (error: Exception) {
            Os.close(fileDescriptor)
            throw error
        }
    }

    override fun openRead(name: String): ApkShareReadAuthority {
        val fileDescriptor =
            Os.open(
                child(name).path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        return try {
            PlatformApkShareReadAuthority(fileDescriptor)
        } finally {
            Os.close(fileDescriptor)
        }
    }

    override fun lstat(name: String): ApkShareFileIdentity? = platformLstat(child(name).path)

    override fun rename(
        sourceName: String,
        targetName: String,
    ) = Os.rename(child(sourceName).path, child(targetName).path)

    override fun remove(name: String) = Os.remove(child(name).path)

    override fun sync() = Os.fsync(descriptor.fileDescriptor)

    override fun close() = descriptor.close()

    private fun child(name: String): File = File(anchoredRoot, name)
}

private class PlatformApkShareWriteAuthority(descriptor: FileDescriptor) :
    ApkShareWriteAuthority {
    private val stream = FileOutputStream(descriptor)

    override val output: OutputStream = stream

    override fun identity(): ApkShareFileIdentity = platformIdentity(Os.fstat(stream.fd))

    override fun syncAndMakeReadOnly() {
        stream.fd.sync()
        Os.fchmod(stream.fd, FILE_OWNER_READ_ONLY)
        stream.fd.sync()
    }

    override fun close() = stream.close()
}

private class PlatformApkShareReadAuthority(descriptor: FileDescriptor) :
    ApkShareReadAuthority {
    private val descriptor = ParcelFileDescriptor.dup(descriptor)

    override fun identity(): ApkShareFileIdentity = platformIdentity(Os.fstat(descriptor.fileDescriptor))

    override fun duplicateForGrant(): ParcelFileDescriptor = ParcelFileDescriptor.dup(descriptor.fileDescriptor)

    override fun close() = descriptor.close()
}

/** Host-only implementation for descriptor-authority tests. */
internal object NioApkShareFileOps : ApkShareFileOps {
    override fun openDirectory(path: File): ApkShareDirectoryAuthority {
        val directory = path.toPath().toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        try {
            Files.createDirectory(directory)
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS)
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            // The exact nofollow identity is validated by the caller.
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("APK-share path is not a nofollow directory")
        }
        return NioApkShareDirectoryAuthority(directory)
    }

    override fun lstat(path: File): ApkShareFileIdentity? = nioLstat(path.toPath())

    override fun effectiveUid(): Int = (Files.getAttribute(Paths.get("."), "unix:uid") as Number).toInt()

    override fun effectiveGid(): Int = (Files.getAttribute(Paths.get("."), "unix:gid") as Number).toInt()
}

private class NioApkShareDirectoryAuthority(private val root: Path) :
    ApkShareDirectoryAuthority {
    override fun identity(): ApkShareFileIdentity = requireNotNull(nioLstat(root))

    override fun listNames(): List<String> =
        Files.newDirectoryStream(root).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }

    override fun createExclusive(name: String): ApkShareWriteAuthority? {
        val child = child(name)
        val options =
            setOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS,
            )
        val channel =
            try {
                FileChannel.open(child, options)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                return null
            }
        Files.setPosixFilePermissions(child, FILE_WRITE_PERMISSIONS)
        return NioApkShareWriteAuthority(child, channel)
    }

    override fun openRead(name: String): ApkShareReadAuthority =
        NioApkShareReadAuthority(
            child(name),
            FileChannel.open(child(name), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        )

    override fun lstat(name: String): ApkShareFileIdentity? = nioLstat(child(name))

    override fun rename(
        sourceName: String,
        targetName: String,
    ) {
        Files.move(
            child(sourceName),
            child(targetName),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun remove(name: String) {
        Files.delete(child(name))
    }

    override fun sync() {
        FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
    }

    override fun close() = Unit

    private fun child(name: String): Path = root.resolve(name)
}

private class NioApkShareWriteAuthority(
    private val path: Path,
    private val channel: FileChannel,
) : ApkShareWriteAuthority {
    private var retainedIdentity: ApkShareFileIdentity? = null
    override val output: OutputStream = Channels.newOutputStream(channel)

    override fun identity(): ApkShareFileIdentity {
        val pathIdentity = nioLstat(path)
        if (pathIdentity != null) {
            retainedIdentity = pathIdentity
            return pathIdentity
        }
        return requireNotNull(retainedIdentity).copy(size = channel.size())
    }

    override fun syncAndMakeReadOnly() {
        channel.force(true)
        Files.setPosixFilePermissions(path, FILE_READ_PERMISSIONS)
        channel.force(true)
    }

    override fun close() = channel.close()
}

private class NioApkShareReadAuthority(
    private val path: Path,
    private val channel: FileChannel,
) :
    ApkShareReadAuthority {
    private val retainedIdentity = requireNotNull(nioLstat(path))

    override fun identity(): ApkShareFileIdentity =
        nioLstat(path)?.takeIf(retainedIdentity::sameObject)
            ?: retainedIdentity.copy(size = channel.size())

    override fun duplicateForGrant(): ParcelFileDescriptor = ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun close() = channel.close()
}

private fun platformLstat(path: String): ApkShareFileIdentity? =
    try {
        platformIdentity(Os.lstat(path))
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

private fun platformIdentity(stat: StructStat): ApkShareFileIdentity =
    ApkShareFileIdentity(
        device = stat.st_dev,
        inode = stat.st_ino,
        uid = stat.st_uid,
        gid = stat.st_gid,
        mode = stat.st_mode,
        linkCount = stat.st_nlink,
        size = stat.st_size,
    )

private fun nioLstat(path: Path): ApkShareFileIdentity? =
    try {
        ApkShareFileIdentity(
            device = unixNumber(path, "dev").toLong(),
            inode = unixNumber(path, "ino").toLong(),
            uid = unixNumber(path, "uid").toInt(),
            gid = unixNumber(path, "gid").toInt(),
            mode = unixNumber(path, "mode").toInt(),
            linkCount = unixNumber(path, "nlink").toLong(),
            size = unixNumber(path, "size").toLong(),
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

private fun unixNumber(
    path: Path,
    attribute: String,
): Number = Files.getAttribute(path, "unix:$attribute", LinkOption.NOFOLLOW_LINKS) as Number

private const val DIRECTORY_TYPE = 16384 // 0040000
private const val REGULAR_TYPE = 32768 // 0100000
private const val DIRECTORY_OWNER_ONLY = 448 // 0700
private const val FILE_OWNER_READ_WRITE = 384 // 0600
private const val FILE_OWNER_READ_ONLY = 256 // 0400
private const val OPEN_DIRECTORY_FLAG = 65536 // Linux O_DIRECTORY, 00200000
private val DIRECTORY_PERMISSIONS =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )
private val FILE_WRITE_PERMISSIONS =
    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
private val FILE_READ_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ)
