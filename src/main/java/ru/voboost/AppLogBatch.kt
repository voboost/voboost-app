package ru.voboost

import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import ru.voboost.ota.AndroidDaemonSocketFactory
import ru.voboost.ota.DaemonSocketFactory
import ru.voboost.ota.LocalSocketDaemonOtaGateway
import ru.voboost.ota.socketOperationWithDeadline
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val APP_LOG_MAX_BATCH_RECORDS = 64
internal const val APP_LOG_MAX_BATCH_BYTES = 256 * 1024
internal const val APP_LOG_MAX_BATCH_AGE_MS = 250L
internal const val APP_LOG_MAX_SPOOL_BYTES = 2 * 1024 * 1024L
internal const val APP_LOG_SOCKET_TIMEOUT_MS = 3_000

internal data class AppLogRecord(
    val level: String,
    val component: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
) {
    init {
        require(level == "debug" || level == "info" || level == "error")
        // Byte-identical to the daemon's canonical_log contract: components
        // are bounded in UTF-8 bytes, not UTF-16 chars, and no ISO control
        // survives anywhere in a record the daemon must accept.
        require(component.isNotBlank() && component.toByteArray(Charsets.UTF_8).size <= 64)
        require(component.none(Char::isISOControl) && message.none(Char::isISOControl))
        require(message.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        require(fields.size <= 32)
    }

    fun json(): JSONObject =
        JSONObject()
            .put("level", level)
            .put("component", component)
            .put("message", message)
            .put("fields", JSONObject(fields))
}

internal data class AppLogBatch(val id: String, val records: List<AppLogRecord>) {
    init {
        require(BATCH_ID.matches(id))
        require(records.isNotEmpty() && records.size <= APP_LOG_MAX_BATCH_RECORDS)
        require(encoded().size <= APP_LOG_MAX_BATCH_BYTES)
    }

    fun encoded(): ByteArray =
        JSONObject()
            .put("schemaVersion", 1)
            .put("batchId", id)
            .put("records", JSONArray(records.map(AppLogRecord::json)))
            .toString()
            .toByteArray(Charsets.UTF_8)

    companion object {
        private val BATCH_ID = Regex("[0-9a-f]{32}")

        fun new(records: List<AppLogRecord>): AppLogBatch = AppLogBatch(UUID.randomUUID().toString().replace("-", ""), records)
    }
}

internal fun interface AppLogBatchSender {
    /** Returns the acknowledged batch ID. */
    fun send(encodedBatch: ByteArray): String
}

internal class DaemonAppLogBatchSender(
    private val sockets: DaemonSocketFactory =
        AndroidDaemonSocketFactory(
            LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM),
            APP_LOG_SOCKET_TIMEOUT_MS,
        ),
) : AppLogBatchSender {
    override fun send(encodedBatch: ByteArray): String {
        require(encodedBatch.isNotEmpty() && encodedBatch.size <= APP_LOG_MAX_BATCH_BYTES)
        return sockets.create().use { socket ->
            socket.connect()
            require(socket.peerCredentials().uid == LocalSocketDaemonOtaGateway.EXPECTED_DAEMON_UID) { "Untrusted log socket peer" }
            socketOperationWithDeadline("log-exchange", APP_LOG_SOCKET_TIMEOUT_MS, socket::abort) {
                val output = DataOutputStream(socket.output)
                output.writeInt(encodedBatch.size)
                output.write(encodedBatch)
                output.flush()
                val input = DataInputStream(socket.input)
                val length = input.readInt()
                require(length == 32)
                val ack = ByteArray(length).also(input::readFully).toString(Charsets.US_ASCII)
                require(ack.matches(Regex("[0-9a-f]{32}"))) { "Malformed app log ACK" }
                ack
            }
        }
    }

    private companion object {
        const val SOCKET_PATH = "/data/voboost/run/daemon.sock"
    }
}

/** App-private, atomic retry spool. Canonical persistence remains daemon-owned. */
internal class AppLogSpool(
    private val directory: File,
    private val sender: AppLogBatchSender,
    private val maximumBytes: Long = APP_LOG_MAX_SPOOL_BYTES,
    private val fileOps: AppFileOps = platformAppFileOps,
) {
    private data class SpoolFile(val file: File, val identity: AppFileIdentity)

    private var overflowEpisodeReported = false
    private var mismatchEpisodeReported = false

    @Synchronized
    fun persist(batch: AppLogBatch): File {
        require(directory.isDirectory || directory.mkdirs())
        val destination = writeAtomic(batch)
        val removedError = trimToBound()
        if (removedError && !overflowEpisodeReported) {
            overflowEpisodeReported = true
            val loss =
                writeAtomic(
                    AppLogBatch.new(
                        listOf(
                            AppLogRecord(
                                "error",
                                "app-log-spool",
                                "messages were discarded because the app log spool was full",
                                mapOf("event" to "spool-overflow-loss"),
                            ),
                        ),
                    ),
                )
            trimToBound(reportErrorLoss = false, protected = loss)
        }
        return destination
    }

    private fun writeAtomic(batch: AppLogBatch): File {
        val destination = File(directory, "batch-${batch.id}.json")
        val bytes = batch.encoded()
        val existing = fileOps.lstat(destination)
        if (existing != null) {
            AppSecureFiles.requireExactFile(
                existing,
                fileOps.effectiveUid(),
                fileOps.effectiveGid(),
                bytes.size.toLong(),
                "Existing app log batch",
            )
            check(
                AppSecureFiles.readBounded(
                    destination,
                    APP_LOG_MAX_BATCH_BYTES.toLong(),
                    fileOps,
                )!!.contentEquals(bytes),
            ) {
                "existing app log batch ID has different bytes"
            }
            return destination
        }
        AppSecureFiles.writeAtomically(
            destination,
            bytes,
            fileOps = fileOps,
            expectedTarget = AppExpectedTarget(null),
            childrenBound = AppSecureFiles.DirectoryChildrenBound.LOG_SPOOL,
        )
        return destination
    }

    @Synchronized
    fun deliverPending(): Int {
        var delivered = 0
        var handled: SpoolFile? = null
        while (true) {
            val entry = pending().firstOrNull { it != handled } ?: break
            handled = entry
            val batchId = entry.file.name.removePrefix("batch-").removeSuffix(".json")
            try {
                val ack = sender.send(read(entry))
                check(ack == batchId) { ACK_MISMATCH_MESSAGE }
                remove(entry)
                mismatchEpisodeReported = false
                delivered++
                handled = null
            } catch (error: Exception) {
                if (!isQuarantinable(error)) throw error
                // A batch the daemon persistently refuses would otherwise block
                // every later batch forever; quarantine it and keep delivering.
                quarantine(entry)
                // The diagnostic uses this same spool. Keep it from generating
                // another diagnostic when the peer also refuses its batch.
                // Only a successful ACK ends the continuous mismatch episode.
                if (!mismatchEpisodeReported) {
                    mismatchEpisodeReported = true
                    Logger.error(
                        "app-log-spool",
                        "one app log batch was quarantined: ${error.javaClass.simpleName}: ${error.message}",
                    )
                }
            }
        }
        if (pending().sumOf { it.identity.size } < maximumBytes) {
            overflowEpisodeReported = false
        }
        return delivered
    }

    /**
     * Only the delivery protocol itself qualifies: an ACK mismatch is permanent
     * for this batch ID, while transport failures (daemon absent, timeout) are
     * retried on the same batch. Bound to [ACK_MISMATCH_MESSAGE] so the
     * mismatch site and this classifier cannot drift apart.
     */
    private fun isQuarantinable(error: Exception): Boolean = error is IllegalStateException && error.message == ACK_MISMATCH_MESSAGE

    /**
     * Move one permanently-rejected batch aside so delivery can continue. The
     * quarantine is bounded: the oldest quarantined batches are removed so a
     * persistently mismatching daemon cannot grow the spool without limit.
     */
    private fun quarantine(entry: SpoolFile) {
        val quarantineName = "${entry.file.name}.quarantined"
        fileOps.openPrivateDirectory(directory).use { authority ->
            AppSecureFiles.requireBoundPrivateDirectory(fileOps, directory, authority)
            authority.rename(entry.file.name, quarantineName)
            authority.sync()
            // Sorted oldest-first: keep the newest MAX_QUARANTINED_BATCHES
            // entries and remove the older overflow.
            quarantinedBatches(authority).dropLast(MAX_QUARANTINED_BATCHES).forEach {
                authority.remove(it.file.name)
            }
            authority.sync()
        }
    }

    private fun quarantinedBatches(authority: AppDirectoryAuthority): List<SpoolFile> =
        authority.listNames()
            .filter { QUARANTINED_FILE.matches(it) }
            .mapNotNull { name ->
                val identity = authority.lstat(name) ?: return@mapNotNull null
                SpoolFile(File(directory, name), identity)
            }
            .sortedWith(
                compareBy({ it.identity.modifiedSeconds }, { it.identity.modifiedNanoseconds }, { it.file.name }),
            )

    private fun pending(): List<SpoolFile> {
        require(directory.isDirectory || directory.mkdirs())
        val directoryAuthority = fileOps.openPrivateDirectory(directory)
        return directoryAuthority.use { authority ->
            AppSecureFiles.requireBoundPrivateDirectory(fileOps, directory, authority)
            authority.listNames()
                .filter { it.matches(BATCH_FILE) }
                .map { name ->
                    val identity =
                        authority.lstat(name)
                            ?: throw IOException("App log batch disappeared")
                    AppSecureFiles.requireExactFile(
                        identity,
                        fileOps.effectiveUid(),
                        fileOps.effectiveGid(),
                        null,
                        "App log batch",
                    )
                    SpoolFile(File(directory, name), identity)
                }
                .sortedWith(
                    compareBy<SpoolFile>(
                        { it.identity.modifiedSeconds },
                        { it.identity.modifiedNanoseconds },
                        { it.file.name },
                    ),
                )
        }
    }

    private fun trimToBound(
        reportErrorLoss: Boolean = true,
        protected: File? = null,
    ): Boolean {
        var files = pending()
        var total = files.sumOf { it.identity.size }
        var removedError = false
        while (total > maximumBytes && files.isNotEmpty()) {
            val removable =
                files.filterNot {
                    it.file == protected || (overflowEpisodeReported && batchIsLossRecord(it))
                }
            if (removable.isEmpty()) break
            val candidate =
                oldestRecord(removable, "debug")
                    ?: oldestRecord(removable, "info")
                    ?: oldestRecord(removable, "error")
                    ?: break
            if (reportErrorLoss && candidate.second == "error") removedError = true
            removeOldestRecord(candidate.first, candidate.second)
            files = pending()
            total = files.sumOf { it.identity.size }
        }
        return removedError
    }

    private fun oldestRecord(
        files: List<SpoolFile>,
        level: String,
    ): Pair<SpoolFile, String>? = files.firstOrNull { batchContainsLevel(it, level) }?.let { it to level }

    private fun removeOldestRecord(
        entry: SpoolFile,
        level: String,
    ) {
        val document = JSONObject(read(entry).toString(Charsets.UTF_8))
        val records = document.getJSONArray("records")
        val replacement = JSONArray()
        var removed = false
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            if (!removed && record.getString("level") == level) {
                removed = true
            } else {
                replacement.put(record)
            }
        }
        check(removed)
        if (replacement.length() == 0) {
            remove(entry)
            return
        }
        document.put("records", replacement)
        AppSecureFiles.writeAtomically(
            entry.file,
            document.toString().toByteArray(Charsets.UTF_8),
            fileOps = fileOps,
            expectedTarget = AppExpectedTarget(entry.identity),
            childrenBound = AppSecureFiles.DirectoryChildrenBound.LOG_SPOOL,
        )
    }

    private fun batchContainsLevel(
        entry: SpoolFile,
        level: String,
    ): Boolean {
        val document = JSONObject(read(entry).toString(Charsets.UTF_8))
        val records = document.getJSONArray("records")
        return (0 until records.length()).any {
            records.getJSONObject(it).getString("level") == level
        }
    }

    private fun batchIsLossRecord(entry: SpoolFile): Boolean {
        val document = JSONObject(read(entry).toString(Charsets.UTF_8))
        val records = document.getJSONArray("records")
        return (0 until records.length()).any { index ->
            records.getJSONObject(index).optJSONObject("fields")
                ?.optString("event") == "spool-overflow-loss"
        }
    }

    private fun read(entry: SpoolFile): ByteArray {
        check(fileOps.lstat(entry.file) == entry.identity) { "app log batch identity changed" }
        val bytes =
            AppSecureFiles.readBounded(entry.file, APP_LOG_MAX_BATCH_BYTES.toLong(), fileOps)
                ?: error("app log batch disappeared")
        check(fileOps.lstat(entry.file) == entry.identity) { "app log batch identity changed" }
        return bytes
    }

    private fun remove(entry: SpoolFile) {
        AppSecureFiles.clearExact(
            entry.file,
            fileOps = fileOps,
            expectedIdentity = entry.identity,
            childrenBound = AppSecureFiles.DirectoryChildrenBound.LOG_SPOOL,
        )
    }

    private companion object {
        val BATCH_FILE = Regex("batch-[0-9a-f]{32}\\.json")
        val QUARANTINED_FILE = Regex("batch-[0-9a-f]{32}\\.json\\.quarantined")
        const val MAX_QUARANTINED_BATCHES = 8
        const val ACK_MISMATCH_MESSAGE = "daemon ACK batch ID mismatch"
    }
}

internal class AppLogBatchBuilder(private val startedAtMs: Long) {
    private val records = mutableListOf<AppLogRecord>()

    /**
     * The FULL encoded envelope, exactly as [AppLogBatch.encoded] will emit it
     * at close (schema prefix, 32-hex batch ID, record separators, closing
     * braces) — never the bare sum of record JSON. A record is admitted only
     * when the resulting batch provably satisfies the close-time size
     * requirement, so a successful `offer` sequence guarantees a valid close.
     */
    private fun fullEnvelopeBytes(candidateRecords: List<AppLogRecord>): Int =
        JSONObject()
            .put("schemaVersion", 1)
            .put("batchId", "0".repeat(32))
            .put("records", JSONArray(candidateRecords.map(AppLogRecord::json)))
            .toString()
            .toByteArray(Charsets.UTF_8)
            .size

    fun offer(
        record: AppLogRecord,
        nowMs: Long,
    ): Boolean {
        if (records.isNotEmpty() &&
            (
                records.size >= APP_LOG_MAX_BATCH_RECORDS ||
                    nowMs - startedAtMs >= APP_LOG_MAX_BATCH_AGE_MS
            )
        ) {
            return false
        }
        // The placeholder ID is exactly as long as the minted one, so this is
        // the true close-time size, not an estimate.
        if (fullEnvelopeBytes(records + record) > APP_LOG_MAX_BATCH_BYTES) {
            return false
        }
        records += record
        return true
    }

    fun mustClose(nowMs: Long): Boolean =
        records.lastOrNull()?.level == "error" ||
            records.size == APP_LOG_MAX_BATCH_RECORDS ||
            fullEnvelopeBytes(records) >= APP_LOG_MAX_BATCH_BYTES ||
            nowMs - startedAtMs >= APP_LOG_MAX_BATCH_AGE_MS

    fun close(): AppLogBatch = AppLogBatch.new(records.toList())
}
