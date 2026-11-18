package ru.voboost

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded, serialized and authority-preserving app JSONL logger. */
class Logger private constructor(
    private val logDirectory: File,
    @Volatile private var currentLevel: Level = Level.INFO,
    private val retentionDays: Int = 7,
    private val consoleOutput: Boolean = false,
    private val fileOps: AppFileOps = platformAppFileOps,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val workerHooks: LoggerWorkerHooks = LoggerWorkerHooks.NONE,
    private val daemonSpool: AppLogSpool? = null,
) {
    enum class Level(val priority: Int, val tag: String) {
        NONE(0, ""),
        ERROR(1, "[-]"),
        INFO(2, "[+]"),
        DEBUG(3, "[*]"),
    }

    private enum class Lifecycle { NEW, RUNNING, STOPPING, STOPPED }

    private val lifecycleLock = Any()
    private val queue = ArrayBlockingQueue<String>(queueCapacity)
    private val dropSignal = AtomicBoolean(false)
    private val dropEpisode = AtomicBoolean(false)
    private val poisoned = AtomicBoolean(false)
    private val localSequence = AtomicLong()
    private val producerStreamId = UUID.randomUUID().toString()

    @Volatile private var lifecycle = Lifecycle.NEW
    private var worker: Thread? = null

    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    private fun timestampFormat() =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun initialize() {
        synchronized(lifecycleLock) {
            if (lifecycle == Lifecycle.RUNNING || lifecycle != Lifecycle.NEW) return
            val directory =
                try {
                    fileOps.openPrivateDirectory(logDirectory.absoluteFile)
                } catch (_: Exception) {
                    lifecycle = Lifecycle.STOPPED
                    poisoned.set(true)
                    return
                }
            try {
                requireBoundDirectory(directory)
                cleanOldLogs(directory)
            } catch (_: Exception) {
                directory.close()
                lifecycle = Lifecycle.STOPPED
                poisoned.set(true)
                return
            }
            lifecycle = Lifecycle.RUNNING
            worker =
                Thread({ runWorker(directory) }, "Logger").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                    start()
                }
        }
        logDebug(
            "Logger",
            "Logger initialized: " +
                "level=${currentLevel.name.lowercase()}, retention=${retentionDays}d",
        )
    }

    fun logError(
        source: String,
        message: String,
    ) = log(Level.ERROR, source, message)

    fun logInfo(
        source: String,
        message: String,
    ) = log(Level.INFO, source, message)

    fun logDebug(
        source: String,
        message: String,
    ) = log(Level.DEBUG, source, message)

    fun setLogLevel(level: String) {
        currentLevel = parseLevel(level)
    }

    fun getLogLevel(): String = currentLevel.name.lowercase()

    internal fun shutdown() {
        val thread =
            synchronized(lifecycleLock) {
                when (lifecycle) {
                    Lifecycle.NEW -> {
                        lifecycle = Lifecycle.STOPPED
                        return
                    }
                    Lifecycle.RUNNING -> lifecycle = Lifecycle.STOPPING
                    Lifecycle.STOPPING -> Unit
                    Lifecycle.STOPPED -> return
                }
                worker
            }
        try {
            thread?.join(SHUTDOWN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lifecycleLock) {
            if (thread?.isAlive == true) {
                poisoned.set(true)
                queue.clear()
                thread.interrupt()
            }
        }
    }

    internal fun queuedRecordCount(): Int = queue.size

    internal fun queueCapacity(): Int = queue.size + queue.remainingCapacity()

    internal fun isPoisoned(): Boolean = poisoned.get()

    @Synchronized
    private fun log(
        level: Level,
        source: String,
        message: String,
    ) {
        val configured = currentLevel
        if (configured == Level.NONE || level.priority > configured.priority) return
        if (lifecycle != Lifecycle.RUNNING || poisoned.get()) return
        val line = encodeRecord(timestampFormat().format(Date()), level, source, message)
        if (!queue.offer(line)) {
            if (level == Level.ERROR) {
                val lower =
                    queue.firstOrNull { JSONObject(it).getString("level") == "debug" }
                        ?: queue.firstOrNull { JSONObject(it).getString("level") == "info" }
                if (lower != null) queue.remove(lower)
                queue.offer(line)
            }
            if (dropEpisode.compareAndSet(false, true)) dropSignal.set(true)
        }
    }

    private fun runWorker(directory: AppDirectoryAuthority) {
        if (daemonSpool != null) {
            runDaemonWorker(directory, daemonSpool)
            return
        }
        try {
            while (lifecycle == Lifecycle.RUNNING || queue.isNotEmpty() || dropSignal.get()) {
                val line =
                    try {
                        queue.poll(WORKER_POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        null
                    }
                if (poisoned.get()) break
                if (dropSignal.compareAndSet(true, false)) appendLine(directory, droppedRecord())
                if (line != null) {
                    workerHooks.beforeRecord()
                    appendLine(directory, line)
                }
                if (queue.isEmpty()) dropEpisode.set(false)
            }
        } catch (_: Exception) {
            poisoned.set(true)
        } finally {
            queue.clear()
            dropSignal.set(false)
            dropEpisode.set(false)
            try {
                directory.close()
            } catch (_: Exception) {
            }
            synchronized(lifecycleLock) {
                worker = null
                lifecycle = Lifecycle.STOPPED
            }
        }
    }

    private fun runDaemonWorker(
        directory: AppDirectoryAuthority,
        spool: AppLogSpool,
    ) {
        try {
            while (lifecycle == Lifecycle.RUNNING || queue.isNotEmpty() || dropSignal.get()) {
                val first = queue.poll(WORKER_POLL_MS, TimeUnit.MILLISECONDS)
                val lines = mutableListOf<String>()
                if (dropSignal.compareAndSet(true, false)) lines += droppedRecord()
                if (first != null) lines += first
                val started = System.currentTimeMillis()
                while (lines.size < APP_LOG_MAX_BATCH_RECORDS) {
                    if (System.currentTimeMillis() - started >= APP_LOG_MAX_BATCH_AGE_MS) break
                    if (lines.any { JSONObject(it).getString("level") == "error" }) break
                    val elapsed = System.currentTimeMillis() - started
                    val remaining = APP_LOG_MAX_BATCH_AGE_MS - elapsed
                    val next =
                        queue.poll(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: break
                    lines += next
                }
                if (lines.isNotEmpty()) {
                    val records =
                        lines.map { line ->
                            val json = JSONObject(line)
                            AppLogRecord(
                                json.getString("level"),
                                // The daemon is the contract owner: it rejects any
                                // ISO control in source/message and bounds the
                                // component to 64 UTF-8 bytes. Conform at the batch
                                // boundary so no otherwise-valid app line can
                                // produce a batch the daemon deterministically
                                // refuses, permanently blocking delivery.
                                json.getString("source").filterNot(Char::isISOControl),
                                json.getString("message").filterNot(Char::isISOControl),
                                producerContext(json),
                            )
                        }
                    var builder = AppLogBatchBuilder(started)
                    records.forEach { record ->
                        if (!builder.offer(record, System.currentTimeMillis())) {
                            spool.persist(builder.close())
                            builder = AppLogBatchBuilder(System.currentTimeMillis())
                            check(builder.offer(record, System.currentTimeMillis()))
                        }
                    }
                    spool.persist(builder.close())
                }
                // Delivery of the durable backlog is retried on every cycle
                // even when no new records arrived, so a recovered sender is
                // drained without waiting for the next producer record, and a
                // delivery failure (IOException) is transient, not poison:
                // only persisting new records invalidates the worker.
                try {
                    spool.deliverPending()
                } catch (_: Exception) {
                }
                if (queue.isEmpty()) dropEpisode.set(false)
            }
        } catch (_: Exception) {
            poisoned.set(true)
        } finally {
            queue.clear()
            try {
                directory.close()
            } catch (_: Exception) {
            }
            synchronized(lifecycleLock) {
                worker = null
                lifecycle = Lifecycle.STOPPED
            }
        }
    }

    private fun appendLine(
        directory: AppDirectoryAuthority,
        line: String,
    ) {
        if (daemonSpool != null) {
            val json = JSONObject(line)
            val record =
                AppLogRecord(
                    json.getString("level"),
                    // Same daemon-contract conformance as the batch worker.
                    json.getString("source").filterNot(Char::isISOControl),
                    json.getString("message").filterNot(Char::isISOControl),
                    producerContext(json),
                )
            daemonSpool.persist(AppLogBatch.new(listOf(record)))
            try {
                daemonSpool.deliverPending()
            } catch (_: Exception) {
            }
            return
        }
        val canonical = canonicalRecord(JSONObject(line))
        if (consoleOutput) println(canonical)
        val bytes = (canonical + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LOG_FILE_BYTES) throw PermanentLogPoison()
        requireBoundDirectory(directory)
        val leaf = "${dateFormat().format(Date())}.jsonl"
        var existing = directory.lstat(leaf)
        if (existing != null) {
            requireLogFile(existing, leaf)
            if (existing.size > MAX_LOG_FILE_BYTES) throw PermanentLogPoison()
            if (existing.size + bytes.size > MAX_LOG_FILE_BYTES) {
                rotate(directory, leaf, existing)
                existing = null
            }
        }

        val createdNew = existing == null
        val authority =
            if (createdNew) {
                directory.createExclusive(leaf)
                    ?: throw IOException("Daily log appeared concurrently")
            } else {
                directory.openAppend(leaf)
            }
        authority.use { opened ->
            val before = opened.identity()
            requireLogFile(before, leaf)
            if (directory.lstat(leaf) != before) throw IOException("Daily log was substituted")
            val originalSize = before.size
            try {
                AppSecureFiles.writeFully(opened, bytes)
                opened.sync()
                val after = opened.identity()
                if (
                    !before.sameObject(after) || after.size != originalSize + bytes.size ||
                    directory.lstat(leaf) != after || after.size > MAX_LOG_FILE_BYTES
                ) {
                    throw IOException("Daily log changed during append")
                }
                requireBoundDirectory(directory)
                if (createdNew) directory.sync()
            } catch (_: Exception) {
                try {
                    opened.truncate(originalSize)
                    opened.sync()
                    val rolledBack = opened.identity()
                    if (
                        !before.sameObject(rolledBack) || rolledBack.size != originalSize ||
                        directory.lstat(leaf) != rolledBack
                    ) {
                        throw IOException("Append rollback lost file authority")
                    }
                } catch (_: Exception) {
                    val poisonedIdentity = opened.identity()
                    if (directory.lstat(leaf) == poisonedIdentity) {
                        directory.remove(leaf)
                        directory.sync()
                    }
                    throw PermanentLogPoison()
                }
            }
        }
    }

    private fun rotate(
        directory: AppDirectoryAuthority,
        activeName: String,
        active: AppFileIdentity,
    ) {
        val rotatedName = "$activeName.1"
        val priorRotated = directory.lstat(rotatedName)
        if (priorRotated != null) {
            requireLogFile(priorRotated, rotatedName)
            if (directory.lstat(rotatedName) != priorRotated) {
                throw IOException("Rotated log was substituted")
            }
            directory.remove(rotatedName)
            if (directory.lstat(rotatedName) != null) {
                throw IOException("Rotated log was recreated during retention")
            }
        }
        if (directory.lstat(activeName) != active) {
            throw IOException("Active log was substituted")
        }
        directory.rename(activeName, rotatedName)
        val rotated =
            directory.lstat(rotatedName)
                ?: throw IOException("Rotated log disappeared")
        requireLogFile(rotated, rotatedName)
        if (
            !rotated.sameObject(active) || rotated.size != active.size ||
            directory.lstat(activeName) != null
        ) {
            throw IOException("Daily log rotation lost exact identity")
        }
        directory.sync()
    }

    private fun cleanOldLogs(directory: AppDirectoryAuthority) {
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -retentionDays) }.time
        val parser = dateFormat()
        directory.listNames().forEach { name ->
            if (!LOG_FILE_PATTERN.matches(name)) return@forEach
            val date =
                parser.parse(name.removeSuffix(".1").removeSuffix(".jsonl"))
                    ?: return@forEach
            val identity = directory.lstat(name) ?: return@forEach
            requireLogFile(identity, name)
            if (date.before(cutoff)) {
                if (directory.lstat(name) != identity) {
                    throw IOException(
                        "Retained log was substituted",
                    )
                }
                directory.remove(name)
                if (directory.lstat(name) != null) {
                    throw IOException("Retained log was recreated during cleanup")
                }
                directory.sync()
            }
        }
    }

    private fun requireBoundDirectory(directory: AppDirectoryAuthority) =
        AppSecureFiles.requireBoundPrivateDirectory(fileOps, logDirectory.absoluteFile, directory)

    private fun requireLogFile(
        identity: AppFileIdentity,
        name: String,
    ) = AppSecureFiles.requireExactFile(
        identity,
        fileOps.effectiveUid(),
        fileOps.effectiveGid(),
        null,
        "Log $name",
    )

    private fun encodeRecord(
        timestamp: String,
        level: Level,
        source: String,
        message: String,
        event: String = "log.line",
    ): String {
        val boundedSource =
            source.filterNot(Char::isISOControl).take(MAX_SOURCE_CHARS)
                .ifBlank { "unknown" }
        val outcome =
            when (level) {
                Level.ERROR -> "failure"
                Level.INFO -> "info"
                Level.DEBUG -> "debug"
                Level.NONE -> "none"
            }
        return JSONObject()
            .put("schemaVersion", LOG_SCHEMA_VERSION)
            .put("timestamp", timestamp)
            .put("level", level.name.lowercase(Locale.US))
            .put("service", "voboost-app")
            .put("source", boundedSource)
            .put("event", event)
            .put("outcome", outcome)
            .put("message", redact(message).take(MAX_MESSAGE_CHARS))
            .toString()
    }

    private fun droppedRecord(): String =
        encodeRecord(
            timestampFormat().format(Date()),
            Level.ERROR,
            "Logger",
            DROPPED_MESSAGE,
            "logger.records_dropped",
        )

    /** Existing ProducerRecord.fields contract; daemon assigns its own time and sequence. */
    private fun producerContext(record: JSONObject): Map<String, String> =
        mapOf(
            "producerTimestamp" to record.getString("timestamp"),
            "producerStreamId" to producerStreamId,
            "event" to record.getString("event"),
            "outcome" to record.getString("outcome"),
        )

    /** Sequence belongs to this standalone writer lifetime, never the daemon stream. */
    private fun canonicalRecord(record: JSONObject): String =
        JSONObject()
            .put("schemaVersion", LOG_SCHEMA_VERSION)
            .put("sequence", localSequence.incrementAndGet())
            .put("timestamp", record.getString("timestamp"))
            .put("source", JSONObject().put("kind", "app"))
            .put("level", record.getString("level"))
            .put("component", record.getString("source"))
            .put("message", record.getString("message"))
            .put("fields", JSONObject(producerContext(record)))
            .toString()

    private fun redact(message: String): String {
        val withoutAuthorization =
            AUTHORIZATION_PATTERN.replace(message) { "Authorization=[REDACTED]" }
        return SECRET_PATTERN.replace(withoutAuthorization) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
    }

    private class PermanentLogPoison : IOException()

    companion object {
        private const val LOG_SCHEMA_VERSION = 1
        private const val MAX_SOURCE_CHARS = 64
        private const val MAX_MESSAGE_CHARS = 2048
        private const val MAX_LOG_FILE_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_QUEUE_CAPACITY = 256
        private const val WORKER_POLL_MS = 100L
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val DROPPED_MESSAGE =
            "One or more log records were dropped because the bounded queue was full"
        private val LOG_FILE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}\\.jsonl(?:\\.1)?")
        private val AUTHORIZATION_PATTERN =
            Regex(
                "(?i)\\bauthorization\\s*[:=]\\s*(?:[a-z][a-z0-9_-]*\\s+)?[^\\s,;]+",
            )
        private val SECRET_PATTERN =
            Regex("(?i)\\b(token|password|cookie|secret)\\s*[:=]\\s*[^\\s,;]+")

        @Volatile private var instance: Logger? = null

        @JvmStatic
        @JvmOverloads
        fun create(
            logDirectory: File,
            level: String = "info",
            retentionDays: Int = 7,
            console: Boolean = false,
        ): Logger = createForTests(logDirectory, level, retentionDays, console)

        internal fun createForTests(
            logDirectory: File,
            level: String = "info",
            retentionDays: Int = 7,
            console: Boolean = false,
            fileOps: AppFileOps = platformAppFileOps,
            queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
            workerHooks: LoggerWorkerHooks = LoggerWorkerHooks.NONE,
            spool: AppLogSpool? = null,
        ): Logger {
            require(queueCapacity > 0)
            return Logger(
                logDirectory,
                parseLevel(level),
                retentionDays,
                console,
                fileOps,
                queueCapacity,
                workerHooks,
                spool,
            ).also(Logger::initialize)
        }

        @JvmStatic @Synchronized @JvmOverloads
        fun init(
            context: Context,
            level: String = "info",
            retentionDays: Int = 7,
            console: Boolean = false,
        ) {
            if (instance == null) {
                val directory = Paths(context).logsDir
                instance =
                    Logger(
                        directory,
                        parseLevel(level),
                        retentionDays,
                        console,
                        platformAppFileOps,
                        DEFAULT_QUEUE_CAPACITY,
                        LoggerWorkerHooks.NONE,
                        AppLogSpool(directory, DaemonAppLogBatchSender()),
                    ).also(Logger::initialize)
            }
        }

        @JvmStatic @Synchronized @JvmOverloads
        fun init(
            logDirectory: File,
            level: String = "info",
            retentionDays: Int = 7,
            console: Boolean = false,
        ) {
            if (instance == null) instance = create(logDirectory, level, retentionDays, console)
        }

        @JvmStatic fun error(
            source: String,
            message: String,
        ) = instance?.logError(source, message)

        @JvmStatic fun info(
            source: String,
            message: String,
        ) = instance?.logInfo(source, message)

        @JvmStatic fun debug(
            source: String,
            message: String,
        ) = instance?.logDebug(source, message)

        @JvmStatic fun setLevel(level: String) = instance?.setLogLevel(level)

        @JvmStatic fun getLevel(): String = instance?.getLogLevel() ?: "info"

        @JvmStatic @Synchronized
        fun shutdown() {
            instance?.shutdown()
            instance = null
        }

        private fun parseLevel(level: String): Level =
            try {
                Level.valueOf(level.uppercase())
            } catch (_: IllegalArgumentException) {
                Level.INFO
            }
    }
}

internal fun interface LoggerWorkerHooks {
    fun beforeRecord()

    companion object {
        val NONE = LoggerWorkerHooks { }
    }
}
