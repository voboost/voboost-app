package ru.voboost

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LoggerTest {
    private lateinit var testDir: File
    private lateinit var logger: Logger

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "voboost-log-${UUID.randomUUID()}")
        testDir.mkdirs()
        logger = Logger.create(testDir, "debug", 7)
    }

    @After
    fun tearDown() {
        logger.shutdown()
        Logger.shutdown()
        testDir.deleteRecursively()
    }

    @Test
    fun existingLocalReaderRoundTripsCanonicalFixture() {
        logger.shutdown()
        val canonical =
            JSONObject()
                .put("schemaVersion", 1).put("sequence", 7)
                .put("timestamp", "2026-10-26T09:00:00.000Z")
                .put("source", JSONObject().put("kind", "app"))
                .put("level", "error").put("component", "fixture")
                .put("message", "quoted \"text\" and newline\nvalue")
                .put(
                    "fields",
                    JSONObject().put("producerStreamId", "fixture-stream")
                        .put("producerTimestamp", "2026-10-26T09:00:00.000Z")
                        .put("event", "test.failure").put("outcome", "failure"),
                )
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        AppSecureFiles.writeAtomically(File(testDir, "$today.jsonl"), (canonical.toString() + "\n").toByteArray())
        val restored = records().single()
        assertEquals(canonical.toString(), restored.toString())
    }

    @Test
    fun canonicalWireShapePreservesProducerContext() {
        logger.logInfo("wire", "line\n\"escaped\"")
        logger.shutdown()
        val record = records().last()
        assertTrue(record.getLong("sequence") > 0)
        assertEquals(
            setOf("schemaVersion", "sequence", "timestamp", "source", "level", "component", "message", "fields"),
            record.keys().asSequence().toSet(),
        )
        assertEquals("app", record.getJSONObject("source").getString("kind"))
        assertFalse(record.getJSONObject("source").has("id"))
        assertEquals("wire", record.getString("component"))
        val fields = record.getJSONObject("fields")
        assertEquals("log.line", fields.getString("event"))
        assertEquals("info", fields.getString("outcome"))
        assertEquals(record.getString("timestamp"), fields.getString("producerTimestamp"))
        assertTrue(fields.getString("producerStreamId").isNotBlank())
    }

    @Test
    fun localWriterIdentityChangesAcrossLoggerLifetimes() {
        logger.logInfo("identity", "first")
        logger.shutdown()
        val first = records().last().getJSONObject("fields").getString("producerStreamId")
        logger = Logger.create(testDir, "debug", 7)
        logger.logInfo("identity", "second")
        logger.shutdown()
        val second = records().last().getJSONObject("fields").getString("producerStreamId")
        assertFalse(first == second)
    }

    @Test
    fun actualDaemonSpoolWorkerPreservesProducerContextAndThreeLevels() {
        logger.shutdown()
        val sent = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val spool =
            AppLogSpool(
                testDir,
                AppLogBatchSender { bytes ->
                    val batch = JSONObject(bytes.toString(Charsets.UTF_8))
                    val rows = batch.getJSONArray("records")
                    repeat(rows.length()) { sent += rows.getJSONObject(it) }
                    batch.getString("batchId")
                },
            )
        logger = Logger.createForTests(testDir, "debug", spool = spool)
        logger.logDebug("wire", "debug")
        logger.logInfo("wire", "info")
        logger.logError("wire", "error")
        logger.shutdown()
        val rows = sent.filter { it.getString("component") == "wire" }
        assertEquals(listOf("debug", "info", "error"), rows.map { it.getString("level") })
        rows.forEach { row ->
            assertEquals(setOf("level", "component", "message", "fields"), row.keys().asSequence().toSet())
            val fields = row.getJSONObject("fields")
            assertEquals("log.line", fields.getString("event"))
            assertTrue(fields.getString("producerTimestamp").endsWith("Z"))
            assertTrue(fields.getString("producerStreamId").isNotBlank())
        }
    }

    @Test
    fun everyPhysicalLineIsOneTypedJsonObject() {
        logger.logInfo("test", "first\nsecond\tvalue")
        logger.logError("worker", "failed")
        logger.shutdown()

        val records = records()
        assertTrue(records.size >= 3)
        records.forEach { record ->
            assertEquals(1, record.getInt("schemaVersion"))
            assertEquals("app", record.getJSONObject("source").getString("kind"))
            assertEquals("log.line", record.getJSONObject("fields").getString("event"))
            assertTrue(record.getString("timestamp").endsWith("Z"))
        }
        assertTrue(records.any { it.getString("message") == "first\nsecond\tvalue" })
    }

    @Test
    fun secretsAreRedactedBeforePersistence() {
        logger.logError(
            "ota",
            "token=abc123 Authorization: Bearer bearer-value " +
                "Authorization: Basic dXNlcjpwYXNz cookie=session-value safe=visible",
        )
        logger.shutdown()

        val text = logFiles().joinToString("\n") { it.readText() }
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("bearer-value"))
        assertFalse(text.contains("dXNlcjpwYXNz"))
        assertFalse(text.contains("session-value"))
        assertTrue(text.contains("[REDACTED]"))
        assertTrue(text.contains("safe=visible"))
    }

    @Test
    fun `source and message fields are bounded`() {
        logger.logInfo("s".repeat(200), "m".repeat(5000))
        logger.shutdown()

        val record = records().last()
        assertEquals(64, record.getString("component").length)
        assertEquals(2048, record.getString("message").length)
    }

    @Test
    fun `concurrent producers are serialized without torn JSON`() {
        val threads = 6
        val perThread = 25
        val latch = CountDownLatch(threads)
        repeat(threads) { thread ->
            Thread {
                repeat(perThread) { index -> logger.logInfo("thread-$thread", "message-$index") }
                latch.countDown()
            }.start()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        logger.shutdown()

        val messages = records().count { it.getString("component").startsWith("thread-") }
        assertEquals(threads * perThread, messages)
        val sequences = records().map { it.getLong("sequence") }
        assertEquals(sequences.sorted().distinct(), sequences)
    }

    @Test
    fun `level filtering retains only allowed records`() {
        logger.setLogLevel("error")
        logger.logInfo("filter", "hidden")
        logger.logError("filter", "visible")
        logger.shutdown()

        val messages = records().map { it.getString("message") }
        assertFalse(messages.contains("hidden"))
        assertTrue(messages.contains("visible"))
    }

    @Test
    fun `daily file rotates at the byte bound`() {
        logger.shutdown()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        AppSecureFiles.writeAtomically(
            File(testDir, "$today.jsonl"),
            ByteArray(4 * 1024 * 1024) { 'x'.code.toByte() },
        )
        logger = Logger.create(testDir, "info", 7)
        logger.logInfo("rotation", "after rotation")
        logger.shutdown()

        assertTrue(File(testDir, "$today.jsonl.1").isFile)
        assertTrue(File(testDir, "$today.jsonl").readText().contains("after rotation"))
    }

    @Test
    fun `static facade writes the same JSONL schema`() {
        logger.shutdown()
        Logger.init(testDir, "debug", 7)
        Logger.debug("static", "record")
        Logger.shutdown()

        assertTrue(records().any { it.getString("component") == "static" })
    }

    @Test
    fun distinctErrorsSurviveToCapacityAndAllErrorOverflowIsReported() {
        logger.shutdown()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        logger =
            Logger.createForTests(
                testDir, "debug", queueCapacity = 3,
                workerHooks =
                    LoggerWorkerHooks {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    },
            )
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(3) { logger.logError("failure", "distinct-$it") }
        repeat(1000) { logger.logDebug("burst", "debug") }
        logger.logError("failure", "overflow-error")
        release.countDown()
        logger.shutdown()
        val rows = records()
        repeat(3) { index -> assertTrue(rows.any { it.getString("message") == "distinct-$index" }) }
        assertEquals(1, rows.count { it.getJSONObject("fields").optString("event") == "logger.records_dropped" })
        assertFalse(rows.any { it.getString("message") == "overflow-error" })
    }

    @Test
    fun debugSaturationCannotEvictAnError() {
        logger.shutdown()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        logger =
            Logger.createForTests(
                testDir, "debug", queueCapacity = 2,
                workerHooks =
                    LoggerWorkerHooks {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    },
            )
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        logger.logDebug("burst", "debug-one")
        logger.logInfo("burst", "info-two")
        logger.logError("failure", "must-survive")
        repeat(1000) { logger.logDebug("burst", "later-debug") }
        release.countDown()
        logger.shutdown()
        assertTrue(records().any { it.getString("message") == "must-survive" })
    }

    @Test
    fun queueSaturationRemainsBoundedAndEmitsOneFixedDiagnostic() {
        logger.shutdown()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        logger =
            Logger.createForTests(
                testDir,
                "debug",
                queueCapacity = 4,
                workerHooks =
                    LoggerWorkerHooks {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    },
            )
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(1_000) { logger.logInfo("burst", "caller-secret-$it") }
        assertTrue(logger.queuedRecordCount() <= logger.queueCapacity())
        release.countDown()
        logger.shutdown()

        val dropped = records().filter { it.getJSONObject("fields").getString("event") == "logger.records_dropped" }
        assertEquals(1, dropped.size)
        assertEquals(
            "One or more log records were dropped because the bounded queue was full",
            dropped.single().getString("message"),
        )
        assertFalse(dropped.single().toString().contains("caller-secret"))
    }

    @Test
    fun shortWritesAndEintrRetryWhilePartialErrorsRollbackOrPoison() {
        logger.shutdown()
        val faults = FaultingAppFileOps(platformAppFileOps)
        logger = Logger.createForTests(testDir, "debug", fileOps = faults)
        waitUntil { records().isNotEmpty() }

        faults.mode = WriteFault.SHORT_WITH_EINTR
        logger.logInfo("fault", "short write survives")
        waitUntil { records().any { it.optString("message") == "short write survives" } }

        faults.mode = WriteFault.PARTIAL_ROLLBACK
        logger.logInfo("fault", "rolled back partial")
        waitUntil { faults.mode == WriteFault.NONE }
        logger.logInfo("fault", "after rollback")
        logger.shutdown()
        assertFalse(records().any { it.optString("message") == "rolled back partial" })
        assertTrue(records().any { it.optString("message") == "after rollback" })

        logger = Logger.createForTests(testDir, "debug", fileOps = faults)
        waitUntil { !logger.isPoisoned() }
        faults.mode = WriteFault.PARTIAL_POISON
        logger.logInfo("fault", "poisoning partial")
        waitUntil { logger.isPoisoned() }
        logger.logInfo("fault", "must be rejected")
        logger.shutdown()
        assertFalse(records().any { it.optString("message") == "must be rejected" })
    }

    @Test
    fun `hostile log leaves and shutdown reinitialization fail closed without caller crashes`() {
        logger.shutdown()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        logFiles().forEach(File::delete)
        val victim = File(testDir, "victim").apply { writeText("victim") }
        Files.createSymbolicLink(File(testDir, "$today.jsonl").toPath(), victim.toPath())
        logger = Logger.create(testDir, "debug", 7)
        waitUntil { logger.isPoisoned() }
        logger.logInfo("caller", "rejected safely")
        logger.shutdown()
        assertEquals("victim", victim.readText())

        Files.delete(File(testDir, "$today.jsonl").toPath())
        Files.createLink(File(testDir, "$today.jsonl").toPath(), victim.toPath())
        logger = Logger.create(testDir, "debug", 7)
        waitUntil { logger.isPoisoned() }
        logger.shutdown()
        assertEquals("victim", victim.readText())
        Files.delete(File(testDir, "$today.jsonl").toPath())

        Logger.init(testDir, "debug", 7)
        Logger.info("reinit", "first lifecycle")
        Logger.shutdown()
        Logger.info("reinit", "post-shutdown rejection")
        Logger.init(testDir, "debug", 7)
        Logger.info("reinit", "second lifecycle")
        Logger.shutdown()
        assertTrue(records().any { it.optString("message") == "second lifecycle" })
    }

    private fun logFiles(): List<File> =
        testDir.listFiles()
            ?.filter { it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.jsonl(?:\\.1)?")) }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun records(): List<JSONObject> =
        logFiles().flatMap { file ->
            val content = file.readText()
            val complete =
                if (content.endsWith(
                        '\n',
                    )
                ) {
                    content
                } else {
                    content.substringBeforeLast('\n', "")
                }
            complete.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
        }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(predicate())
    }
}

private enum class WriteFault { NONE, SHORT_WITH_EINTR, PARTIAL_ROLLBACK, PARTIAL_POISON }

private class FaultingAppFileOps(private val delegate: AppFileOps) : AppFileOps by delegate {
    private val interrupted = AtomicBoolean(false)
    private val partialWritten = AtomicBoolean(false)

    @Volatile
    var mode = WriteFault.NONE
        set(value) {
            field = value
            interrupted.set(false)
            partialWritten.set(false)
        }

    override fun openPrivateDirectory(path: File): AppDirectoryAuthority = FaultingDirectory(delegate.openPrivateDirectory(path), this)

    fun wrap(authority: AppWriteAuthority): AppWriteAuthority =
        object : AppWriteAuthority by authority {
            override fun write(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int =
                when (mode) {
                    WriteFault.NONE -> authority.write(buffer, offset, length)
                    WriteFault.SHORT_WITH_EINTR -> {
                        if (interrupted.compareAndSet(false, true)) throw InterruptedIOException()
                        authority.write(buffer, offset, minOf(3, length))
                    }
                    WriteFault.PARTIAL_ROLLBACK, WriteFault.PARTIAL_POISON -> {
                        if (partialWritten.compareAndSet(false, true)) {
                            authority.write(buffer, offset, minOf(5, length))
                        } else {
                            throw IOException("simulated partial append failure")
                        }
                    }
                }

            override fun truncate(size: Long) {
                if (mode == WriteFault.PARTIAL_POISON) {
                    throw IOException(
                        "simulated rollback failure",
                    )
                }
                authority.truncate(size)
                if (mode == WriteFault.PARTIAL_ROLLBACK) mode = WriteFault.NONE
            }
        }
}

private class FaultingDirectory(
    private val delegate: AppDirectoryAuthority,
    private val faults: FaultingAppFileOps,
) : AppDirectoryAuthority by delegate {
    override fun createExclusive(name: String): AppWriteAuthority? = delegate.createExclusive(name)?.let(faults::wrap)

    override fun openAppend(name: String): AppWriteAuthority =
        faults.wrap(
            delegate.openAppend(name),
        )
}
