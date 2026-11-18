package ru.voboost.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.voboost.AppFileIdentity
import ru.voboost.AppFileOps
import ru.voboost.AppReadAuthority
import ru.voboost.AppSecureFiles
import ru.voboost.AppWriteAuthority
import ru.voboost.FILE_OWNER_READ_ONLY
import ru.voboost.platformAppFileOps
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal interface OtaTransportClient {
    suspend fun fetch(
        url: URI,
        maximumBytes: Long,
    ): ByteArray

    suspend fun download(
        manifestUrl: URI,
        artifact: ProductArtifact,
        directory: File,
        transactionId: String,
    ): File
}

internal interface OtaAuthorityHooks {
    fun afterDirectoryOpened(directory: File) = Unit

    fun afterPartialOpened(partial: File) = Unit

    fun beforePublish(
        partial: File,
        final: File,
    ) = Unit

    companion object {
        val NONE = object : OtaAuthorityHooks {}
    }
}

internal class OtaHttpTransport(
    private val callFactory: Call.Factory =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    private val allowLoopbackHttp: Boolean = false,
    private val fileOps: AppFileOps = platformAppFileOps,
    private val authorityHooks: OtaAuthorityHooks = OtaAuthorityHooks.NONE,
    additionalRedirectOrigins: Set<String> = emptySet(),
) : OtaTransportClient {
    private val additionalRedirectOrigins = additionalRedirectOrigins.toSet()

    override suspend fun fetch(
        url: URI,
        maximumBytes: Long,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            requireBound(maximumBytes in 1..MAX_DOCUMENT_BYTES, "Invalid document bound")
            execute(url, null).use { lease ->
                val response = lease.response
                requireBound(response.code == 200, "HTTP ${response.code}")
                val body = response.body ?: throw ProductContractException("HTTP response has no body")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        requireBound(total <= maximumBytes, "HTTP body exceeds bound")
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            }
        }

    override suspend fun download(
        manifestUrl: URI,
        artifact: ProductArtifact,
        directory: File,
        transactionId: String,
    ): File =
        withContext(Dispatchers.IO) {
            requireBound(transactionId.matches(TRANSACTION_PATTERN), "Invalid transaction ID")
            val finalName = "$transactionId--${artifact.file}"
            val partialName = "$finalName.part"
            val partial = File(directory, partialName)
            val final = File(directory, finalName)
            fileOps.openPrivateDirectory(directory).use { retained ->
                AppSecureFiles.requireBoundPrivateDirectory(fileOps, directory, retained)
                authorityHooks.afterDirectoryOpened(directory)
                AppSecureFiles.requireBoundPrivateDirectory(fileOps, directory, retained)
                val uid = fileOps.effectiveUid()
                val gid = fileOps.effectiveGid()
                retained.lstat(finalName)?.let { identity ->
                    requireReadOnly(identity, uid, gid, artifact.size)
                    retained.openRead(finalName).use { opened ->
                        requireBound(opened.identity() == identity, "Final artifact changed before read")
                        requireBound(hash(opened, artifact.size) == artifact.sha256, "Final artifact SHA-256 mismatch")
                        requireBound(opened.identity() == identity, "Final artifact changed while hashing")
                    }
                    return@withContext final
                }
                val initial = retained.lstat(partialName)
                val authority =
                    if (initial == null) {
                        retained.createExclusive(partialName)
                            ?: throw ProductContractException("Exclusive OTA partial creation raced")
                    } else if (requireSealedPartial(initial, uid, gid, artifact)) {
                        // A previous run sealed this partial (0400) but was cut
                        // between the chmod/fsync and the rename. The bytes
                        // were fully hash-verified before sealing, so verify
                        // them again read-only and publish them by rename:
                        // the mover needs no file write permission, and the
                        // sealed mode is exactly the final read-only contract.
                        retained.openRead(partialName).use { sealed ->
                            val sealedIdentity = sealed.identity()
                            requireReadOnly(sealedIdentity, uid, gid, artifact.size)
                            requireBound(hash(sealed, artifact.size) == artifact.sha256, "Sealed artifact SHA-256 mismatch")
                            requireBound(sealed.identity().sameObject(sealedIdentity), "Sealed artifact changed while hashing")
                        }
                        requireBound(retained.lstat(partialName) == initial, "Sealed OTA partial changed before publish")
                        requireBound(retained.lstat(finalName) == null, "OTA final target appeared before publish")
                        retained.rename(partialName, finalName)
                        retained.sync()
                        val published = retained.lstat(finalName) ?: throw ProductContractException("Published artifact disappeared")
                        requireReadOnly(published, uid, gid, artifact.size)
                        requireBound(published.sameObject(initial), "Published artifact identity mismatch")
                        return@withContext final
                    } else {
                        requireWritable(initial, uid, gid)
                        retained.openAppend(partialName)
                    }
                authority.use { opened ->
                    val openedIdentity = opened.identity()
                    requireWritable(openedIdentity, uid, gid)
                    requireBound(initial == null || initial == openedIdentity, "OTA partial changed before open")
                    authorityHooks.afterPartialOpened(partial)
                    AppSecureFiles.requireBoundPrivateDirectory(fileOps, directory, retained)
                    requireBound(retained.lstat(partialName) == openedIdentity, "OTA partial path was substituted")
                    var offset = openedIdentity.size
                    requireBound(offset <= artifact.size, "Partial artifact exceeds signed size")
                    if (offset == artifact.size) {
                        if (hash(opened, artifact.size) != artifact.sha256) {
                            opened.truncate(0)
                            opened.seek(0)
                            offset = 0
                        }
                    }
                    if (offset < artifact.size) {
                        val url = artifactUrl(manifestUrl, artifact.file, allowLoopbackHttp)
                        execute(url, offset.takeIf { it > 0 }).use { lease ->
                            val response = lease.response
                            when {
                                offset > 0 && response.code == 206 -> verifyContentRange(response, offset, artifact.size)
                                response.code == 200 -> {
                                    opened.truncate(0)
                                    opened.seek(0)
                                    offset = 0
                                }
                                else -> throw ProductContractException("Artifact HTTP status ${response.code} is invalid")
                            }
                            val body = response.body ?: throw ProductContractException("Artifact response has no body")
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var total = offset
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    requireBound(total <= artifact.size, "Artifact exceeds signed size")
                                    writeFully(opened, buffer, read)
                                }
                                requireBound(total == artifact.size, "Artifact size is incomplete")
                            }
                        }
                    }
                    opened.sync()
                    val written = opened.identity()
                    requireWritable(written, uid, gid, artifact.size)
                    requireBound(retained.lstat(partialName) == written, "OTA partial changed after write")
                    requireBound(hash(opened, artifact.size) == artifact.sha256, "Artifact SHA-256 mismatch")
                    requireBound(opened.identity() == written, "OTA partial changed while hashing")
                    opened.setMode(FILE_OWNER_READ_ONLY)
                    opened.sync()
                    val sealed = opened.identity()
                    requireReadOnly(sealed, uid, gid, artifact.size)
                    authorityHooks.beforePublish(partial, final)
                    AppSecureFiles.requireBoundPrivateDirectory(fileOps, directory, retained)
                    requireBound(retained.lstat(partialName) == sealed, "OTA partial changed before publish")
                    requireBound(retained.lstat(finalName) == null, "OTA final target appeared before publish")
                    retained.rename(partialName, finalName)
                    opened.publishedAs(finalName)
                    retained.sync()
                    val published = retained.lstat(finalName) ?: throw ProductContractException("Published artifact disappeared")
                    requireReadOnly(published, uid, gid, artifact.size)
                    requireBound(published.sameObject(sealed), "Published artifact identity mismatch")
                    requireBound(opened.identity().sameObject(sealed), "Published descriptor identity mismatch")
                }
            }
            final
        }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun execute(
        initial: URI,
        rangeStart: Long?,
    ): CallResponse {
        var url = validateTransportUrl(initial)
        val initialOrigin = origin(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request =
                Request.Builder().url(url.toASCIIString()).apply {
                    if (rangeStart != null) header("Range", "bytes=$rangeStart-")
                }.build()
            val call = callFactory.newCall(request)
            val cancellation =
                coroutineContext[Job]?.invokeOnCompletion(
                    onCancelling = true,
                    invokeImmediately = true,
                ) { cause ->
                    if (cause is kotlinx.coroutines.CancellationException) call.cancel()
                }
            val response =
                try {
                    call.execute()
                } catch (error: Exception) {
                    cancellation?.dispose()
                    throw error
                }
            val lease = CallResponse(response, cancellation)
            if (response.code !in 300..399) return lease
            val location = response.header("Location")
            lease.close()
            requireBound(redirectCount < MAX_REDIRECTS && location != null, "Redirect limit exceeded")
            val redirected = validateTransportUrl(url.resolve(location))
            requireBound(
                origin(redirected) == initialOrigin || origin(redirected) in additionalRedirectOrigins,
                "Redirect origin is not allowed",
            )
            url = redirected
        }
        throw ProductContractException("Redirect limit exceeded")
    }

    private class CallResponse(
        val response: Response,
        private val cancellation: kotlinx.coroutines.DisposableHandle?,
    ) : AutoCloseable {
        override fun close() {
            response.close()
            cancellation?.dispose()
        }
    }

    private fun validateTransportUrl(value: URI): URI {
        if (
            allowLoopbackHttp &&
            value.scheme == "http" &&
            value.host in setOf("localhost", "127.0.0.1", "::1") &&
            value.rawUserInfo == null &&
            value.rawFragment == null
        ) {
            return value
        }
        return parseHttpsUrl(value.toASCIIString())
    }

    private fun origin(uri: URI): String = "${uri.scheme}://${uri.host.lowercase()}${if (uri.port == -1) "" else ":${uri.port}"}"

    companion object {
        const val MAX_DOCUMENT_BYTES = 4L * 1024L * 1024L
        const val MAX_SIGNATURE_BYTES = 64L
        const val MAX_REDIRECTS = 2
        private val TRANSACTION_PATTERN = Regex("^[0-9a-f]{64}$")

        fun signatureUrl(document: URI): URI {
            val path = document.rawPath
            requireBound(path.endsWith(".json"), "Signed document URL must end with .json")
            parseHttpsUrl(document.toASCIIString())
            val query = document.rawQuery?.let { "?$it" }.orEmpty()
            return parseHttpsUrl("${document.scheme}://${document.rawAuthority}$path.sig$query")
        }

        fun artifactUrl(
            manifestUrl: URI,
            file: String,
            allowLoopbackHttp: Boolean = false,
        ): URI {
            val path = manifestUrl.rawPath
            val basePath = path.substringBeforeLast('/', "") + "/"
            requireBound(
                file.isNotEmpty() && file !in setOf(".", "..") &&
                    file.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() },
                "Invalid artifact basename",
            )
            val encodedFile = URI(null, null, file, null).rawPath
            val resolved = URI("${manifestUrl.scheme}://${manifestUrl.rawAuthority}$basePath$encodedFile")
            if (
                allowLoopbackHttp &&
                resolved.scheme == "http" &&
                resolved.host in setOf("localhost", "127.0.0.1", "::1") && resolved.rawUserInfo == null
            ) {
                return resolved
            }
            return parseHttpsUrl(resolved.toASCIIString())
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun hash(
            authority: AppReadAuthority,
            expectedSize: Long,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = authority.read(buffer, 0, buffer.size)
                if (read < 0) break
                total += read
                requireBound(total <= expectedSize, "Artifact hash exceeds signed size")
                digest.update(buffer, 0, read)
            }
            requireBound(total == expectedSize, "Artifact hash size mismatch")
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun hash(
            authority: AppWriteAuthority,
            expectedSize: Long,
        ): String {
            authority.seek(0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = authority.read(buffer, 0, buffer.size)
                if (read < 0) break
                total += read
                requireBound(total <= expectedSize, "Artifact hash exceeds signed size")
                digest.update(buffer, 0, read)
            }
            requireBound(total == expectedSize, "Artifact hash size mismatch")
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun writeFully(
            authority: AppWriteAuthority,
            bytes: ByteArray,
            length: Int,
        ) {
            var offset = 0
            while (offset < length) {
                val written = authority.write(bytes, offset, length - offset)
                requireBound(written > 0, "Artifact write made no progress")
                offset += written
            }
        }

        private fun verifyContentRange(
            response: Response,
            offset: Long,
            total: Long,
        ) {
            val value = response.header("Content-Range") ?: throw ProductContractException("Missing Content-Range")
            val match =
                Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$").matchEntire(value)
                    ?: throw ProductContractException("Invalid Content-Range")
            requireBound(match.groupValues[1].toLong() == offset, "Content-Range start mismatch")
            requireBound(match.groupValues[3].toLong() == total, "Content-Range total mismatch")
            requireBound(match.groupValues[2].toLong() == total - 1, "Content-Range end mismatch")
        }

        /** A sealed-to-0400, digest-complete `.part` left by a cut before rename. */
        private fun requireSealedPartial(
            identity: AppFileIdentity,
            uid: Int,
            gid: Int,
            artifact: ProductArtifact,
        ): Boolean =
            identity.isRegular() && identity.uid == uid && identity.gid == gid &&
                identity.mode and 0x1ff == FILE_OWNER_READ_ONLY && identity.linkCount == 1L &&
                identity.size == artifact.size

        private fun requireWritable(
            identity: AppFileIdentity,
            uid: Int,
            gid: Int,
            size: Long? = null,
        ) {
            requireBound(
                identity.isRegular() && identity.uid == uid && identity.gid == gid &&
                    identity.mode and 0x1ff == 0x180 && identity.linkCount == 1L &&
                    (size == null || identity.size == size),
                "OTA partial authority is unsafe: uid=${identity.uid}/$uid gid=${identity.gid}/$gid " +
                    "mode=${identity.mode and 0x1ff} links=${identity.linkCount} size=${identity.size}",
            )
        }

        private fun requireReadOnly(
            identity: AppFileIdentity,
            uid: Int,
            gid: Int,
            size: Long,
        ) {
            requireBound(
                identity.isRegular() && identity.uid == uid && identity.gid == gid &&
                    identity.mode and 0x1ff == FILE_OWNER_READ_ONLY && identity.linkCount == 1L &&
                    identity.size == size,
                "OTA final authority is unsafe",
            )
        }

        private fun requireBound(
            condition: Boolean,
            message: String,
        ) {
            if (!condition) throw ProductContractException(message)
        }
    }
}
