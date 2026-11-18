package ru.voboost.ota

import ru.voboost.AppFileIdentity
import ru.voboost.AppFileOps
import ru.voboost.AppReadAuthority
import ru.voboost.AppSecureFiles
import ru.voboost.Paths
import ru.voboost.platformAppFileOps
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

internal enum class OtaTransactionPhase { DOWNLOADING, READY, PROPOSED }

internal data class OtaTransactionRecord(
    val manifestSha256: String,
    val transactionId: String,
    val releaseIdentity: String,
    val phase: OtaTransactionPhase,
)

internal class OtaTransactionStore(
    private val paths: Paths,
    private val fileOps: AppFileOps = platformAppFileOps,
) {
    private val random = SecureRandom()

    fun transactionId(
        manifestSha256: String,
        releaseIdentity: String,
    ): String {
        requireSha256(manifestSha256)
        ProductSemVer.parse(releaseIdentity)
        val existing = readRecord()
        if (existing != null) {
            if (existing.manifestSha256 == manifestSha256 && existing.releaseIdentity == releaseIdentity) {
                return existing.transactionId
            }
            // A newer channel release supersedes any not-yet-installed
            // transaction (downloading, ready or proposed): a hotfix must be
            // able to retire a bad older release quickly instead of forcing
            // its installation first.
            retire(existing)
        }
        val id = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        writeRecord(OtaTransactionRecord(manifestSha256, id, releaseIdentity, OtaTransactionPhase.DOWNLOADING))
        return id
    }

    fun stageDocuments(
        transactionId: String,
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
    ) {
        requireTransaction(transactionId)
        require(signatureBytes.size == 64) { "Manifest signature must be 64 bytes" }
        AppSecureFiles.writeAtomically(
            artifactFile(transactionId, MANIFEST),
            manifestBytes,
            fileOps,
            childrenBound = AppSecureFiles.DirectoryChildrenBound.OTA_STAGING,
        )
        AppSecureFiles.writeAtomically(
            artifactFile(transactionId, MANIFEST_SIGNATURE),
            signatureBytes,
            fileOps,
            childrenBound = AppSecureFiles.DirectoryChildrenBound.OTA_STAGING,
        )
    }

    fun directory(): File = paths.otaDir

    fun artifactFile(
        transactionId: String,
        file: String,
    ): File {
        requireTransaction(transactionId)
        require(file.isNotEmpty() && file.none { it == '/' || it == '\\' || it == '\u0000' })
        return File(paths.otaDir, "$transactionId--$file")
    }

    fun publishReady(record: OtaTransactionRecord) {
        require(record.phase == OtaTransactionPhase.READY)
        AppSecureFiles.writeAtomically(
            artifactFile(record.transactionId, READY),
            "${record.manifestSha256}\n${record.releaseIdentity}\n".toByteArray(Charsets.US_ASCII),
            fileOps,
            childrenBound = AppSecureFiles.DirectoryChildrenBound.OTA_STAGING,
        )
        writeRecord(record)
    }

    fun markProposed(record: OtaTransactionRecord) {
        require(record.phase == OtaTransactionPhase.PROPOSED)
        writeRecord(record)
    }

    fun current(): OtaTransactionRecord? = readRecord()

    fun reconcile(terminal: DaemonTerminalAcknowledgement?) {
        val record = readRecord() ?: return
        if (terminal == null) return
        if (
            terminal.transactionId != record.transactionId ||
            terminal.manifestSha256 != record.manifestSha256 ||
            terminal.releaseIdentity != record.releaseIdentity
        ) {
            return
        }
        if (terminal.outcome == DaemonTerminalOutcome.COMPLETE) retire(record)
    }

    fun openCandidate(
        record: OtaTransactionRecord,
        manifest: ProductManifest,
        manifestSignature: ByteArray,
        changed: List<ProductArtifact>,
    ): OtaCandidateFiles {
        require(record.phase == OtaTransactionPhase.READY || record.phase == OtaTransactionPhase.PROPOSED)
        val expected =
            listOf(
                CandidateArtifactIdentity(MANIFEST, manifest.exactBytes.size.toLong(), record.manifestSha256),
                CandidateArtifactIdentity(MANIFEST_SIGNATURE, manifestSignature.size.toLong(), sha256(manifestSignature)),
            ) + changed.map { CandidateArtifactIdentity(it.file, it.size, it.sha256) }
        val directory = fileOps.openPrivateDirectory(paths.otaDir)
        val opened = mutableListOf<AppReadAuthority>()
        try {
            AppSecureFiles.requireBoundPrivateDirectory(fileOps, paths.otaDir, directory)
            val uid = fileOps.effectiveUid()
            val gid = fileOps.effectiveGid()
            val handles =
                expected.mapIndexed { index, identity ->
                    val child = "${record.transactionId}--${identity.file}"
                    val pathIdentity = directory.lstat(child) ?: throw ProductContractException("Candidate file is missing: $child")
                    requireSafeFile(pathIdentity, uid, gid, identity.size, control = index < 2)
                    val authority = directory.openRead(child)
                    opened += authority
                    require(authority.identity() == pathIdentity) { "Candidate changed before descriptor open" }
                    require(hash(authority, identity.size) == identity.sha256) { "Candidate descriptor SHA-256 mismatch" }
                    require(authority.identity() == pathIdentity) { "Candidate changed while hashing" }
                    authority.seek(0)
                    RetainedOtaFile(identity.file, identity.size, identity.sha256, pathIdentity, authority)
                }
            AppSecureFiles.requireBoundPrivateDirectory(fileOps, paths.otaDir, directory)
            handles.forEach { handle ->
                require(directory.lstat("${record.transactionId}--${handle.file}") == handle.identity) {
                    "Candidate path changed after descriptor verification"
                }
            }
            return OtaCandidateFiles(handles) {
                opened.asReversed().forEach { runCatching { it.close() } }
                directory.close()
            }
        } catch (error: Exception) {
            opened.asReversed().forEach { runCatching { it.close() } }
            directory.close()
            throw error
        }
    }

    private fun readRecord(): OtaTransactionRecord? {
        if (fileOps.lstat(paths.otaTransactionFile) == null) return null
        val bytes =
            requireNotNull(
                AppSecureFiles.readBounded(paths.otaTransactionFile, MAX_RECORD_BYTES, fileOps),
            ) { "OTA transaction state disappeared" }
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        if (lines.size != 5 || lines.last().isNotEmpty()) throw ProductContractException("OTA transaction state is malformed")
        requireSha256(lines[0])
        requireTransaction(lines[1])
        ProductSemVer.parse(lines[2])
        val phase =
            runCatching {
                OtaTransactionPhase.valueOf(
                    lines[3],
                )
            }.getOrElse { throw ProductContractException("OTA phase is invalid") }
        return OtaTransactionRecord(lines[0], lines[1], lines[2], phase)
    }

    private fun writeRecord(record: OtaTransactionRecord) {
        AppSecureFiles.writeAtomically(
            paths.otaTransactionFile,
            "${record.manifestSha256}\n${record.transactionId}\n${record.releaseIdentity}\n${record.phase.name}\n".toByteArray(
                Charsets.US_ASCII,
            ),
            fileOps,
        )
    }

    private fun retire(record: OtaTransactionRecord) {
        fileOps.openPrivateDirectory(paths.otaDir).use { directory ->
            AppSecureFiles.requireBoundPrivateDirectory(fileOps, paths.otaDir, directory)
            val names = directory.listNames()
            require(names.size <= MAX_CHILDREN && names.toSet().size == names.size) { "OTA directory inventory is not bounded" }
            val uid = fileOps.effectiveUid()
            val gid = fileOps.effectiveGid()
            names.filter { it.startsWith("${record.transactionId}--") }.forEach { name ->
                val identity = directory.lstat(name) ?: throw ProductContractException("Retired candidate disappeared")
                requireSafeFile(identity, uid, gid, identity.size, control = true)
                directory.remove(name)
            }
            directory.sync()
        }
        AppSecureFiles.clearExact(paths.otaTransactionFile, fileOps)
    }

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
            require(read > 0) { "Candidate descriptor made no progress" }
            total += read
            require(total <= expectedSize) { "Candidate descriptor exceeds bound" }
            digest.update(buffer, 0, read)
        }
        require(total == expectedSize) { "Candidate descriptor size mismatch" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireSafeFile(
        identity: AppFileIdentity,
        uid: Int,
        gid: Int,
        size: Long,
        control: Boolean,
    ) {
        val mode = identity.mode and 0x1ff
        require(
            identity.isRegular() && identity.uid == uid && identity.gid == gid &&
                identity.linkCount == 1L && identity.size == size &&
                mode in if (control) setOf(0x100, 0x180) else setOf(0x100),
        ) { "Candidate file authority is unsafe" }
    }

    private fun requireTransaction(value: String) {
        require(value.matches(TRANSACTION)) { "Transaction ID is invalid" }
    }

    private fun requireSha256(value: String) {
        require(value.matches(SHA256)) { "SHA-256 is invalid" }
    }

    companion object {
        private const val MANIFEST = "voboost.json"
        private const val MANIFEST_SIGNATURE = "voboost.json.sig"
        private const val READY = "ready"
        private const val MAX_RECORD_BYTES = 320L
        private const val MAX_CHILDREN = 2052
        private val TRANSACTION = Regex("^[0-9a-f]{64}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
