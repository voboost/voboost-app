package ru.voboost

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

/** Exact authority metadata for one app-private filesystem object. */
internal data class AppFileIdentity(
    val device: Long,
    val inode: Long,
    val uid: Int,
    val gid: Int,
    val mode: Int,
    val linkCount: Long,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanoseconds: Long,
    val changedSeconds: Long,
    val changedNanoseconds: Long,
) {
    fun sameObject(other: AppFileIdentity): Boolean = device == other.device && inode == other.inode

    fun isDirectory(): Boolean = mode and FILE_TYPE_MASK == DIRECTORY_TYPE

    fun isRegular(): Boolean = mode and FILE_TYPE_MASK == REGULAR_TYPE
}

/** Optional expected pathname state; a wrapped null means the target must be absent. */
internal data class AppExpectedTarget(val identity: AppFileIdentity?)

internal interface AppReadAuthority : AutoCloseable {
    fun identity(): AppFileIdentity

    fun duplicateForTransfer(): ParcelFileDescriptor

    fun seek(offset: Long)

    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

internal interface AppWriteAuthority : AutoCloseable {
    fun identity(): AppFileIdentity

    fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun seek(offset: Long)

    fun truncate(size: Long)

    fun setMode(mode: Int)

    fun sync()

    fun publishedAs(name: String) = Unit
}

internal interface AppDirectoryAuthority : AutoCloseable {
    fun identity(): AppFileIdentity

    fun createExclusive(name: String): AppWriteAuthority?

    fun openAppend(name: String): AppWriteAuthority

    fun openRead(name: String): AppReadAuthority

    fun lstat(name: String): AppFileIdentity?

    fun rename(
        sourceName: String,
        targetName: String,
    )

    fun remove(name: String)

    fun listNames(): List<String>

    fun sync()
}

internal interface AppFileOps {
    fun openPrivateDirectory(path: File): AppDirectoryAuthority

    fun lstat(path: File): AppFileIdentity?

    fun effectiveUid(): Int

    fun effectiveGid(): Int
}

/** Deterministic mutation points used only by hostile-path unit tests. */
internal interface AppFileMutationHooks {
    fun tempName(
        secureDefault: String,
        attempt: Int,
    ): String = secureDefault

    fun afterTempCreated(
        tempName: String,
        targetName: String,
    ) = Unit

    fun beforePublish(
        tempName: String,
        targetName: String,
    ) = Unit

    fun afterPublish(
        tempName: String,
        targetName: String,
    ) = Unit

    fun afterRead(targetName: String) = Unit

    companion object {
        val NONE = object : AppFileMutationHooks {}
    }
}

/**
 * App-private file transactions rooted in one retained directory authority.
 *
 * Production child operations resolve below `/proc/self/fd/<dirfd>` and never
 * reopen a caller path. Host unit tests use the same contract with NIO.
 */
internal object AppSecureFiles {
    private const val TEMP_CREATE_ATTEMPTS = 16
    private const val MAX_STALE_TEMP_BYTES = 1024L * 1024L
    private val random = SecureRandom()

    /** Typed directory purposes share one bounded-enumeration contract. */
    enum class DirectoryChildrenBound(val maximum: Int) {
        /** Small control documents and one-file-per-target documents. */
        DOCUMENTS(64),

        /**
         * App log spool: durable batches are each bounded to
         * [ru.voboost.APP_LOG_MAX_BATCH_BYTES] and the byte quota is the
         * primary bound; the child count headroom must cover a valid
         * sender-outage backlog, not a global 64-entry ceiling.
         */
        LOG_SPOOL(1024),

        /**
         * OTA staging: up to 251 changed artifacts (gateway admission
         * bound) plus manifest/signature control files, ready marker and
         * in-flight `.part` resume files, matched to the store's retire
         * contract.
         */
        OTA_STAGING(2052),
    }

    @Synchronized
    fun writeAtomically(
        target: File,
        bytes: ByteArray,
        fileOps: AppFileOps = platformAppFileOps,
        hooks: AppFileMutationHooks = AppFileMutationHooks.NONE,
        expectedTarget: AppExpectedTarget? = null,
        childrenBound: DirectoryChildrenBound = DirectoryChildrenBound.DOCUMENTS,
    ) {
        val absoluteTarget = target.absoluteFile
        val parent = absoluteTarget.parentFile ?: throw IOException("File has no parent")
        fileOps.openPrivateDirectory(parent).use { directory ->
            requireBoundPrivateDirectory(fileOps, parent, directory)
            val expectedUid = fileOps.effectiveUid()
            val expectedGid = fileOps.effectiveGid()
            val initialTarget = directory.lstat(absoluteTarget.name)
            if (expectedTarget != null && initialTarget != expectedTarget.identity) {
                throw IOException("Target pathname differs from the required identity")
            }
            if (initialTarget != null) {
                requireExactFile(initialTarget, expectedUid, expectedGid, null, "Existing target")
            }
            reconcileOwnedTemps(
                directory,
                fileOps,
                absoluteTarget.name,
                maxOf(bytes.size.toLong(), MAX_STALE_TEMP_BYTES),
                childrenBound,
            )

            val temporary = createOwnedTemp(directory, fileOps, absoluteTarget.name, hooks)
            var published = false
            try {
                temporary.authority.use { authority ->
                    hooks.afterTempCreated(temporary.name, absoluteTarget.name)
                    writeFully(authority, bytes)
                    authority.sync()
                    val written = authority.identity()
                    requireExactFile(
                        written,
                        expectedUid,
                        expectedGid,
                        bytes.size.toLong(),
                        "Written temp",
                    )
                    if (!temporary.created.sameObject(written)) {
                        throw IOException("Owned temp descriptor identity changed")
                    }

                    hooks.beforePublish(temporary.name, absoluteTarget.name)
                    requireBoundPrivateDirectory(fileOps, parent, directory)
                    if (directory.lstat(temporary.name) != written) {
                        throw IOException("Owned temp pathname was substituted")
                    }
                    if (directory.lstat(absoluteTarget.name) != initialTarget) {
                        throw IOException("Target pathname was substituted")
                    }

                    directory.rename(temporary.name, absoluteTarget.name)
                    published = true
                    authority.publishedAs(absoluteTarget.name)
                    hooks.afterPublish(temporary.name, absoluteTarget.name)

                    val afterPublish = authority.identity()
                    requireExactFile(
                        afterPublish,
                        expectedUid,
                        expectedGid,
                        bytes.size.toLong(),
                        "Published target",
                    )
                    if (
                        !afterPublish.sameObject(written) ||
                        directory.lstat(absoluteTarget.name) != afterPublish
                    ) {
                        throw IOException("Published target lost retained descriptor authority")
                    }
                    if (directory.lstat(temporary.name) != null) {
                        throw IOException("Published temp alias still exists")
                    }
                    requireBoundPrivateDirectory(fileOps, parent, directory)
                    directory.sync()
                }
            } finally {
                if (!published) cleanupExact(directory, temporary.name, temporary.created)
            }
        }
    }

    fun readBounded(
        target: File,
        maxBytes: Long,
        fileOps: AppFileOps = platformAppFileOps,
        hooks: AppFileMutationHooks = AppFileMutationHooks.NONE,
    ): ByteArray? {
        require(maxBytes >= 0 && maxBytes <= Int.MAX_VALUE)
        val absoluteTarget = target.absoluteFile
        val parent = absoluteTarget.parentFile ?: throw IOException("File has no parent")
        fileOps.openPrivateDirectory(parent).use { directory ->
            requireBoundPrivateDirectory(fileOps, parent, directory)
            val pathBefore = directory.lstat(absoluteTarget.name) ?: return null
            requireExactFile(
                pathBefore,
                fileOps.effectiveUid(),
                fileOps.effectiveGid(),
                null,
                "Stored file",
            )
            if (pathBefore.size > maxBytes) throw IOException("Stored file exceeds byte bound")
            directory.openRead(absoluteTarget.name).use { authority ->
                val descriptorBefore = authority.identity()
                if (descriptorBefore != pathBefore) throw IOException("Stored file was substituted")
                val output = ByteArray(descriptorBefore.size.toInt())
                var offset = 0
                while (offset < output.size) {
                    val count = readRetrying(authority, output, offset, output.size - offset)
                    if (count < 0) throw IOException("Stored file was truncated while reading")
                    if (count == 0) continue
                    offset += count
                }
                val probe = ByteArray(1)
                if (readRetrying(authority, probe, 0, 1) >= 0) {
                    throw IOException("Stored file grew while reading")
                }
                hooks.afterRead(absoluteTarget.name)
                val descriptorAfter = authority.identity()
                if (
                    descriptorAfter != descriptorBefore ||
                    directory.lstat(absoluteTarget.name) != descriptorAfter
                ) {
                    throw IOException("Stored file changed while reading")
                }
                requireBoundPrivateDirectory(fileOps, parent, directory)
                return output
            }
        }
    }

    fun clearExact(
        target: File,
        fileOps: AppFileOps = platformAppFileOps,
        hooks: AppFileMutationHooks = AppFileMutationHooks.NONE,
        expectedIdentity: AppFileIdentity? = null,
        childrenBound: DirectoryChildrenBound = DirectoryChildrenBound.DOCUMENTS,
    ) {
        val absoluteTarget = target.absoluteFile
        val parent = absoluteTarget.parentFile ?: throw IOException("File has no parent")
        fileOps.openPrivateDirectory(parent).use { directory ->
            requireBoundPrivateDirectory(fileOps, parent, directory)
            requireBoundedInventory(directory, childrenBound)
            val identity = directory.lstat(absoluteTarget.name) ?: return
            if (expectedIdentity != null && identity != expectedIdentity) {
                throw IOException("Cleared file differs from the required identity")
            }
            requireExactFile(
                identity,
                fileOps.effectiveUid(),
                fileOps.effectiveGid(),
                null,
                "Cleared file",
            )
            hooks.beforePublish("", absoluteTarget.name)
            requireBoundPrivateDirectory(fileOps, parent, directory)
            if (directory.lstat(absoluteTarget.name) != identity) {
                throw IOException("Cleared file pathname was substituted")
            }
            directory.remove(absoluteTarget.name)
            if (directory.lstat(absoluteTarget.name) != null) {
                throw IOException("Cleared file pathname was recreated")
            }
            directory.sync()
        }
    }

    /** Bounded enumeration stays mandatory for every purpose; the ceiling is typed. */
    fun requireBoundedInventory(
        directory: AppDirectoryAuthority,
        childrenBound: DirectoryChildrenBound,
    ) {
        val names = directory.listNames()
        if (names.size > childrenBound.maximum || names.size != names.toSet().size) {
            throw IOException("App-private directory inventory is not bounded")
        }
    }

    fun requireBoundPrivateDirectory(
        fileOps: AppFileOps,
        path: File,
        directory: AppDirectoryAuthority,
    ) {
        val descriptor = directory.identity()
        val expectedMode = DIRECTORY_TYPE or DIRECTORY_OWNER_ONLY
        if (
            !descriptor.isDirectory() ||
            descriptor.uid != fileOps.effectiveUid() ||
            descriptor.gid != fileOps.effectiveGid() ||
            descriptor.mode != expectedMode
        ) {
            throw IOException("App-private directory has unsafe authority")
        }
        val pathIdentity = fileOps.lstat(path)
        if (pathIdentity == null || !pathIdentity.sameObject(descriptor)) {
            throw IOException("App-private directory pathname was substituted")
        }
        if (
            pathIdentity.uid != descriptor.uid ||
            pathIdentity.gid != descriptor.gid ||
            pathIdentity.mode != descriptor.mode
        ) {
            throw IOException("App-private directory authority changed")
        }
    }

    fun requireExactFile(
        identity: AppFileIdentity,
        expectedUid: Int,
        expectedGid: Int,
        expectedSize: Long?,
        label: String,
    ) {
        if (
            !identity.isRegular() ||
            identity.uid != expectedUid ||
            identity.gid != expectedGid ||
            identity.mode != REGULAR_TYPE or FILE_OWNER_READ_WRITE ||
            identity.linkCount != 1L ||
            (expectedSize != null && identity.size != expectedSize)
        ) {
            throw IOException("$label is not an exact owned 0600 single-link regular file")
        }
    }

    fun writeFully(
        authority: AppWriteAuthority,
        bytes: ByteArray,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val count =
                try {
                    authority.write(bytes, offset, bytes.size - offset)
                } catch (_: InterruptedIOException) {
                    continue
                }
            if (count <= 0) throw IOException("File write made no progress")
            offset += count
        }
    }

    private fun readRetrying(
        authority: AppReadAuthority,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        while (true) {
            try {
                return authority.read(bytes, offset, length)
            } catch (_: InterruptedIOException) {
                continue
            }
        }
    }

    private fun createOwnedTemp(
        directory: AppDirectoryAuthority,
        fileOps: AppFileOps,
        targetName: String,
        hooks: AppFileMutationHooks,
    ): OwnedAppTemp {
        repeat(TEMP_CREATE_ATTEMPTS) { attempt ->
            val name = hooks.tempName(newTempName(targetName), attempt)
            requireSafeLeaf(name)
            val authority = directory.createExclusive(name) ?: return@repeat
            try {
                val identity = authority.identity()
                requireExactFile(
                    identity,
                    fileOps.effectiveUid(),
                    fileOps.effectiveGid(),
                    0L,
                    "New temp",
                )
                if (directory.lstat(name) != identity) {
                    throw IOException("New temp pathname lacks descriptor authority")
                }
                return OwnedAppTemp(name, authority, identity)
            } catch (error: Exception) {
                val ownedIdentity = authority.identityOrNull()
                try {
                    authority.close()
                } catch (_: Exception) {
                    // Preserve the authority-establishment error.
                }
                cleanupExact(directory, name, ownedIdentity)
                throw error
            }
        }
        throw IOException("Could not create exclusive app-private temp")
    }

    private fun cleanupExact(
        directory: AppDirectoryAuthority,
        name: String,
        owned: AppFileIdentity?,
    ) {
        if (owned == null) return
        val current = directory.lstat(name)
        if (current != null && current.sameObject(owned)) {
            directory.remove(name)
            if (directory.lstat(name) != null) {
                throw IOException("Owned app temp was recreated during cleanup")
            }
            directory.sync()
        }
    }

    /** Reconcile only exact-owned closed-pattern stale temps for this target. */
    private fun reconcileOwnedTemps(
        directory: AppDirectoryAuthority,
        fileOps: AppFileOps,
        targetName: String,
        maxAggregateBytes: Long,
        childrenBound: DirectoryChildrenBound,
    ) {
        val names = directory.listNames()
        if (names.size > childrenBound.maximum || names.size != names.toSet().size) {
            throw IOException("App-private directory inventory is not bounded")
        }
        val pattern = Regex("^${Regex.escape(targetName)}\\.[0-9a-f]{32}\\.tmp$")
        var aggregate = 0L
        val stale = names.filter(pattern::matches).sorted()
        for (name in stale) {
            val identity = directory.lstat(name) ?: throw IOException("Stale temp disappeared")
            requireExactFile(
                identity,
                fileOps.effectiveUid(),
                fileOps.effectiveGid(),
                null,
                "Stale temp",
            )
            aggregate = Math.addExact(aggregate, identity.size)
            if (aggregate > maxAggregateBytes) {
                throw IOException("Stale app-temp aggregate exceeds its bound")
            }
        }
        if (stale.isEmpty()) return
        for (name in stale) {
            val before = requireNotNull(directory.lstat(name))
            directory.remove(name)
            if (directory.lstat(name) != null) {
                throw IOException("Stale app temp was recreated during reconciliation")
            }
            if (!before.isRegular()) throw IOException("Stale app temp changed type")
        }
        directory.sync()
    }

    private fun requireSafeLeaf(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
            throw IOException("Invalid app-private leaf name")
        }
    }

    private fun newTempName(targetName: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return "$targetName.${bytes.joinToString("") { "%02x".format(it) }}.tmp"
    }
}

private data class OwnedAppTemp(
    val name: String,
    val authority: AppWriteAuthority,
    val created: AppFileIdentity,
)

internal val platformAppFileOps: AppFileOps by lazy {
    if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)) {
        AndroidAppFileOps
    } else {
        HostAppFileOps
    }
}

private object AndroidAppFileOps : AppFileOps {
    override fun openPrivateDirectory(path: File): AppDirectoryAuthority {
        try {
            Os.mkdir(path.path, DIRECTORY_OWNER_ONLY)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST) throw error
        }
        val raw =
            Os.open(
                path.path,
                androidPrivateDirectoryOpenFlags(),
                0,
            )
        val stat = Os.fstat(raw)
        if (
            stat.st_mode and FILE_TYPE_MASK != DIRECTORY_TYPE ||
            stat.st_uid != Os.geteuid() ||
            stat.st_gid != Os.getegid()
        ) {
            Os.close(raw)
            throw IOException("App-private directory is not owned by this process")
        }
        Os.fchmod(raw, DIRECTORY_OWNER_ONLY)
        return try {
            AndroidAppDirectoryAuthority(ParcelFileDescriptor.dup(raw))
        } finally {
            Os.close(raw)
        }
    }

    override fun lstat(path: File): AppFileIdentity? = androidLstat(path.path)

    override fun effectiveUid(): Int = Os.geteuid()

    override fun effectiveGid(): Int = Os.getegid()
}

internal fun androidPrivateDirectoryOpenFlags(): Int = OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW

private class AndroidAppDirectoryAuthority(
    private val descriptor: ParcelFileDescriptor,
) : AppDirectoryAuthority {
    private val root = File("/proc/self/fd/${descriptor.fd}")

    override fun identity(): AppFileIdentity = androidIdentity(Os.fstat(descriptor.fileDescriptor))

    override fun createExclusive(name: String): AppWriteAuthority? {
        val raw =
            try {
                Os.open(
                    child(name).path,
                    OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                    FILE_OWNER_READ_WRITE,
                )
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST) return null
                throw error
            }
        return AndroidAppWriteAuthority(raw)
    }

    override fun openAppend(name: String): AppWriteAuthority =
        AndroidAppWriteAuthority(
            Os.open(
                child(name).path,
                OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            ),
        ).also { it.seek(it.identity().size) }

    override fun openRead(name: String): AppReadAuthority =
        AndroidAppReadAuthority(
            Os.open(
                child(name).path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            ),
        )

    override fun lstat(name: String): AppFileIdentity? = androidLstat(child(name).path)

    override fun rename(
        sourceName: String,
        targetName: String,
    ) = Os.rename(child(sourceName).path, child(targetName).path)

    override fun remove(name: String) = Os.remove(child(name).path)

    override fun listNames(): List<String> = root.list().orEmpty().toList()

    override fun sync() = Os.fsync(descriptor.fileDescriptor)

    override fun close() = descriptor.close()

    private fun child(name: String): File = File(root, name)
}

private class AndroidAppWriteAuthority(
    private val descriptor: FileDescriptor,
) : AppWriteAuthority {
    override fun identity(): AppFileIdentity = androidIdentity(Os.fstat(descriptor))

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        try {
            Os.write(descriptor, buffer, offset, length)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EINTR) throw InterruptedIOException()
            throw IOException(error)
        }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        try {
            Os.read(descriptor, buffer, offset, length)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EINTR) throw InterruptedIOException()
            throw IOException(error)
        }

    override fun seek(offset: Long) {
        Os.lseek(descriptor, offset, OsConstants.SEEK_SET)
    }

    override fun truncate(size: Long) = Os.ftruncate(descriptor, size)

    override fun setMode(mode: Int) = Os.fchmod(descriptor, mode)

    override fun sync() = Os.fsync(descriptor)

    override fun close() = Os.close(descriptor)
}

private class AndroidAppReadAuthority(
    private val descriptor: FileDescriptor,
) : AppReadAuthority {
    override fun identity(): AppFileIdentity = androidIdentity(Os.fstat(descriptor))

    override fun duplicateForTransfer(): ParcelFileDescriptor = ParcelFileDescriptor.dup(descriptor)

    override fun seek(offset: Long) {
        Os.lseek(descriptor, offset, OsConstants.SEEK_SET)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        try {
            Os.read(descriptor, buffer, offset, length)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EINTR) throw InterruptedIOException()
            throw IOException(error)
        }

    override fun close() = Os.close(descriptor)
}

private fun androidLstat(path: String): AppFileIdentity? =
    try {
        androidIdentity(Os.lstat(path))
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

private fun androidIdentity(stat: StructStat): AppFileIdentity =
    AppFileIdentity(
        stat.st_dev,
        stat.st_ino,
        stat.st_uid,
        stat.st_gid,
        stat.st_mode,
        stat.st_nlink,
        stat.st_size,
        stat.st_mtim.tv_sec,
        stat.st_mtim.tv_nsec,
        stat.st_ctim.tv_sec,
        stat.st_ctim.tv_nsec,
    )

private object HostAppFileOps : AppFileOps {
    override fun openPrivateDirectory(path: File): AppDirectoryAuthority {
        if (!path.exists()) {
            Files.createDirectories(path.toPath())
        }
        val identity =
            hostIdentity(path)
                ?: throw IOException("App-private directory disappeared")
        if (
            !identity.isDirectory() ||
            identity.uid != effectiveUid() ||
            identity.gid != effectiveGid()
        ) {
            throw IOException("App-private directory is not owned by this process")
        }
        setOwnerOnlyDirectory(path)
        return HostAppDirectoryAuthority(path)
    }

    override fun lstat(path: File): AppFileIdentity? = hostIdentity(path)

    override fun effectiveUid(): Int = hostIdentity(File("."))!!.uid

    override fun effectiveGid(): Int = hostIdentity(File("."))!!.gid
}

private class HostAppDirectoryAuthority(private val directory: File) : AppDirectoryAuthority {
    private val channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)
    private val openedIdentity = hostIdentity(directory)!!

    override fun identity(): AppFileIdentity = openedIdentity

    override fun createExclusive(name: String): AppWriteAuthority? =
        try {
            HostAppWriteAuthority(
                FileChannel.open(
                    child(name).toPath(),
                    setOf<OpenOption>(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                ),
                child(name),
            ).also { it.setMode(FILE_OWNER_READ_WRITE) }
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            null
        }

    override fun openAppend(name: String): AppWriteAuthority =
        HostAppWriteAuthority(
            FileChannel.open(
                child(name).toPath(),
                setOf<OpenOption>(
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            ),
            child(name),
        ).also { it.seek(it.identity().size) }

    override fun openRead(name: String): AppReadAuthority =
        HostAppReadAuthority(
            FileChannel.open(
                child(name).toPath(),
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ),
            child(name),
        )

    override fun lstat(name: String): AppFileIdentity? = hostIdentity(child(name))

    override fun rename(
        sourceName: String,
        targetName: String,
    ) {
        Files.move(
            child(sourceName).toPath(),
            child(targetName).toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun remove(name: String) {
        Files.delete(child(name).toPath())
    }

    override fun listNames(): List<String> = directory.list().orEmpty().toList()

    override fun sync() = channel.force(true)

    override fun close() = channel.close()

    private fun child(name: String): File = File(directory, name)
}

private class HostAppWriteAuthority(
    private val channel: FileChannel,
    private var path: File,
) : AppWriteAuthority {
    override fun identity(): AppFileIdentity = hostIdentity(path) ?: throw IOException("Opened app-private file path disappeared")

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = channel.write(ByteBuffer.wrap(buffer, offset, length))

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = channel.read(ByteBuffer.wrap(buffer, offset, length))

    override fun seek(offset: Long) {
        channel.position(offset)
    }

    override fun truncate(size: Long) {
        channel.truncate(size)
    }

    override fun setMode(mode: Int) {
        val permissions =
            when (mode) {
                FILE_OWNER_READ_ONLY -> OWNER_READ_PERMISSIONS
                FILE_OWNER_READ_WRITE -> OWNER_READ_WRITE_PERMISSIONS
                else -> throw IOException("Unsupported app-private file mode")
            }
        Files.setPosixFilePermissions(path.toPath(), permissions)
    }

    override fun sync() = channel.force(true)

    override fun publishedAs(name: String) {
        path = File(path.parentFile, name)
    }

    override fun close() = channel.close()
}

private class HostAppReadAuthority(
    private val channel: FileChannel,
    private val path: File,
) : AppReadAuthority {
    private val openedIdentity = hostIdentity(path)!!

    override fun identity(): AppFileIdentity = openedIdentity

    override fun duplicateForTransfer(): ParcelFileDescriptor {
        val duplicate = ParcelFileDescriptor.open(path, ParcelFileDescriptor.MODE_READ_ONLY)
        val current = hostIdentity(path)
        if (current != openedIdentity) {
            duplicate.close()
            throw IOException("Host transfer duplicate differs from retained file")
        }
        return duplicate
    }

    override fun seek(offset: Long) {
        channel.position(offset)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = channel.read(ByteBuffer.wrap(buffer, offset, length))

    override fun close() = channel.close()
}

private fun hostIdentity(file: File): AppFileIdentity? {
    val path = file.toPath()
    val attributes =
        try {
            Files.readAttributes(path, "unix:*", LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
    val modified = attributes["lastModifiedTime"] as java.nio.file.attribute.FileTime
    val changed = attributes["ctime"] as java.nio.file.attribute.FileTime
    return AppFileIdentity(
        (attributes["dev"] as Number).toLong(),
        (attributes["ino"] as Number).toLong(),
        (attributes["uid"] as Number).toInt(),
        (attributes["gid"] as Number).toInt(),
        (attributes["mode"] as Number).toInt(),
        (attributes["nlink"] as Number).toLong(),
        (attributes["size"] as Number).toLong(),
        modified.toMillis() / 1000,
        modified.toMillis() % 1000 * 1_000_000,
        changed.toMillis() / 1000,
        changed.toMillis() % 1000 * 1_000_000,
    )
}

private fun setOwnerOnlyDirectory(path: File) {
    Files.setPosixFilePermissions(
        path.toPath(),
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
    )
}

private fun AppWriteAuthority.identityOrNull(): AppFileIdentity? =
    try {
        identity()
    } catch (_: Exception) {
        null
    }

private val OWNER_READ_WRITE_PERMISSIONS =
    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
private val OWNER_READ_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ)
private const val FILE_TYPE_MASK = 61440 // 0170000
private const val DIRECTORY_TYPE = 16384 // 0040000
private const val REGULAR_TYPE = 32768 // 0100000
private const val FILE_OWNER_READ_WRITE = 384 // 0600
internal const val FILE_OWNER_READ_ONLY = 256 // 0400
private const val DIRECTORY_OWNER_ONLY = 448 // 0700
