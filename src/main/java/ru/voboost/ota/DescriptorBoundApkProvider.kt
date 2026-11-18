package ru.voboost.ota

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.FileNotFoundException
import java.security.SecureRandom

/**
 * Non-path content authority for one delayed PackageInstaller read.
 *
 * A grant owns a duplicate of the exact verified APK descriptor. The staging
 * pathname is durably unlinked before the installer activity is started, so a
 * later OTA cannot change the bytes represented by an already issued URI.
 * Tokens are process-local and one-shot: process death, expiry, revocation, or
 * the first successful open makes the URI unusable without reopening a path.
 */
class DescriptorBoundApkProvider : ContentProvider() {
    override fun attachInfo(
        context: Context,
        info: ProviderInfo,
    ) {
        require(info.authority == AUTHORITY) { "Unexpected APK provider authority" }
        require(!info.exported) { "Descriptor-bound APK provider must not be exported" }
        require(info.grantUriPermissions) { "Descriptor-bound APK provider needs URI grants" }
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = if (registry.metadata(uri, Binder.getCallingUid()) != null) APK_MIME_TYPE else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (selection != null || selectionArgs != null || sortOrder != null) {
            throw SecurityException("APK grant queries do not accept selectors")
        }
        val metadata =
            registry.metadata(uri, Binder.getCallingUid())
                ?: throw SecurityException("Unknown or unauthorized APK grant")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row =
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> DISPLAY_NAME
                    OpenableColumns.SIZE -> metadata.size
                    else -> null
                }
            }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("APK grant is read-only")
        val handle =
            registry.consume(uri, Binder.getCallingUid())
                ?: throw FileNotFoundException(
                    "APK grant is absent, expired, replayed, or unauthorized",
                )
        rescheduleCleanup()
        return try {
            handle.detachParcelFileDescriptor()
        } finally {
            handle.close()
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw SecurityException("APK grant provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("APK grant provider is read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("APK grant provider is read-only")

    companion object {
        internal const val AUTHORITY = "ru.voboost.apk-share"
        internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        internal const val GRANT_TTL_MILLIS = 5 * 60 * 1000L
        internal const val MAX_ACTIVE_GRANTS = 2
        internal const val MAX_ACTIVE_BYTES = ApkInstallerContract.MAX_APK_BYTES

        private const val DISPLAY_NAME = "voboost-update.apk"
        private val secureRandom = SecureRandom()
        private val handler by lazy { Handler(Looper.getMainLooper()) }
        private val registry =
            OneShotApkGrantRegistry(
                authority = AUTHORITY,
                nowMillis = SystemClock::elapsedRealtime,
                tokenProvider = ::newGrantToken,
                ttlMillis = GRANT_TTL_MILLIS,
                maxActiveGrants = MAX_ACTIVE_GRANTS,
                maxActiveBytes = MAX_ACTIVE_BYTES,
            )
        private val cleanupRunnable =
            Runnable {
                registry.purgeExpired()
                rescheduleCleanup()
            }

        internal fun issue(
            descriptor: ParcelFileDescriptor,
            expectedCallerUid: Int,
            size: Long,
        ): Uri {
            val handle = ParcelApkGrantHandle(descriptor)
            val uri =
                try {
                    registry.issue(handle, expectedCallerUid, size)
                } catch (error: Exception) {
                    handle.close()
                    throw error
                }
            rescheduleCleanup()
            return uri
        }

        internal fun revoke(uri: Uri) {
            registry.revoke(uri)
            rescheduleCleanup()
        }

        internal fun isCanonicalGrantUri(uri: Uri): Boolean = OneShotApkGrantRegistry.parseToken(uri, AUTHORITY) != null

        private fun newGrantToken(): String = ByteArray(16).also(secureRandom::nextBytes).joinToString("") { "%02x".format(it) }

        private fun rescheduleCleanup() {
            handler.removeCallbacks(cleanupRunnable)
            registry.millisUntilNextExpiry()?.let { delay ->
                handler.postDelayed(cleanupRunnable, delay.coerceAtLeast(1L))
            }
        }
    }
}

/** Owned descriptor primitive separated from the pure lease table for host tests. */
internal interface ApkGrantHandle : Closeable {
    fun duplicate(): ApkGrantHandle

    /** Transfer the duplicate to ContentProvider.openFile on Android. */
    fun detachParcelFileDescriptor(): ParcelFileDescriptor
}

internal class ParcelApkGrantHandle(descriptor: ParcelFileDescriptor) : ApkGrantHandle {
    private var descriptor: ParcelFileDescriptor? = descriptor

    override fun duplicate(): ApkGrantHandle {
        val current = checkNotNull(descriptor) { "APK grant descriptor is closed" }
        return ParcelApkGrantHandle(ParcelFileDescriptor.dup(current.fileDescriptor))
    }

    override fun detachParcelFileDescriptor(): ParcelFileDescriptor =
        checkNotNull(descriptor) { "APK grant descriptor is closed" }.also { descriptor = null }

    override fun close() {
        descriptor?.close()
        descriptor = null
    }
}

/** Pure synchronized lease table; Android lifecycle plumbing stays in the provider. */
internal class OneShotApkGrantRegistry(
    private val authority: String,
    private val nowMillis: () -> Long,
    private val tokenProvider: () -> String,
    private val ttlMillis: Long,
    private val maxActiveGrants: Int,
    private val maxActiveBytes: Long,
) {
    internal data class Metadata(val size: Long)

    private data class Lease(
        val descriptor: ApkGrantHandle,
        val expectedCallerUid: Int,
        val size: Long,
        val expiresAtMillis: Long,
    )

    private val leases = linkedMapOf<String, Lease>()

    init {
        require(ttlMillis > 0)
        require(maxActiveGrants > 0)
        require(maxActiveBytes > 0)
    }

    @Synchronized
    fun issue(
        descriptor: ApkGrantHandle,
        expectedCallerUid: Int,
        size: Long,
    ): Uri {
        purgeExpiredLocked()
        require(expectedCallerUid >= 0) { "Invalid installer UID" }
        require(size in 1..maxActiveBytes) { "Invalid APK grant size" }
        val activeBytes = leases.values.sumOf(Lease::size)
        if (leases.size >= maxActiveGrants || activeBytes > maxActiveBytes - size) {
            throw IllegalStateException("Descriptor-bound APK grant capacity is exhausted")
        }
        val token = tokenProvider()
        require(TOKEN_PATTERN.matches(token)) { "Invalid APK grant token" }
        check(token !in leases) { "APK grant token collision" }
        leases[token] =
            Lease(
                descriptor = descriptor,
                expectedCallerUid = expectedCallerUid,
                size = size,
                expiresAtMillis = nowMillis() + ttlMillis,
            )
        return grantUri(authority, token)
    }

    @Synchronized
    fun metadata(
        uri: Uri,
        callerUid: Int,
    ): Metadata? {
        purgeExpiredLocked()
        val token = parseToken(uri, authority) ?: return null
        val lease = leases[token] ?: return null
        if (lease.expectedCallerUid != callerUid) return null
        return Metadata(lease.size)
    }

    @Synchronized
    fun consume(
        uri: Uri,
        callerUid: Int,
    ): ApkGrantHandle? {
        purgeExpiredLocked()
        val token = parseToken(uri, authority) ?: return null
        val lease = leases[token] ?: return null
        if (lease.expectedCallerUid != callerUid) return null
        leases.remove(token)
        return try {
            lease.descriptor.duplicate()
        } finally {
            lease.descriptor.close()
        }
    }

    @Synchronized
    fun revoke(uri: Uri) {
        val token = parseToken(uri, authority) ?: return
        leases.remove(token)?.descriptor?.close()
    }

    @Synchronized
    fun purgeExpired() {
        purgeExpiredLocked()
    }

    @Synchronized
    fun millisUntilNextExpiry(): Long? {
        purgeExpiredLocked()
        val next = leases.values.minOfOrNull(Lease::expiresAtMillis) ?: return null
        return (next - nowMillis()).coerceAtLeast(0L)
    }

    @Synchronized
    fun closeAll() {
        leases.values.forEach { it.descriptor.close() }
        leases.clear()
    }

    @Synchronized
    internal fun activeGrantCount(): Int {
        purgeExpiredLocked()
        return leases.size
    }

    private fun purgeExpiredLocked() {
        val now = nowMillis()
        val expired = leases.filterValues { it.expiresAtMillis <= now }.keys.toList()
        expired.forEach { token -> leases.remove(token)?.descriptor?.close() }
    }

    internal companion object {
        val TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")

        fun grantUri(
            authority: String,
            token: String,
        ): Uri =
            Uri.Builder().scheme("content").authority(authority).appendPath("grant")
                .appendPath("$token.apk").build()

        fun parseToken(
            uri: Uri,
            authority: String,
        ): String? {
            if (
                uri.scheme != "content" ||
                uri.authority != authority ||
                uri.pathSegments.size != 2 ||
                uri.pathSegments[0] != "grant" ||
                uri.query != null ||
                uri.fragment != null
            ) {
                return null
            }
            val leaf = uri.pathSegments[1]
            if (!leaf.endsWith(".apk")) return null
            return leaf.removeSuffix(".apk").takeIf(TOKEN_PATTERN::matches)
        }
    }
}
