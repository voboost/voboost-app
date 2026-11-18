package ru.voboost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppLogSpoolLoggingTest {
    @Test
    fun singletonMismatchDiagnosticDoesNotAmplifyAndRecoversForLaterFailures() {
        val parent = File("build/test-work").absoluteFile.apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "spool-logging-").toFile()
        val acknowledged = AtomicBoolean(false)
        val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
        val spool =
            AppLogSpool(
                directory,
                sender = { bytes ->
                    val batch = JSONObject(bytes.toString(Charsets.UTF_8))
                    val id = batch.getString("batchId")
                    val ack =
                        if (acknowledged.get()) {
                            id
                        } else if (id == "0".repeat(32)) {
                            "1".repeat(32)
                        } else {
                            "0".repeat(32)
                        }
                    val records = batch.getJSONArray("records")
                    repeat(records.length()) { sent += records.getJSONObject(it) }
                    ack
                },
            )
        Logger.shutdown()
        val logger = Logger.createForTests(directory, "info", spool = spool)
        // Match production: spool diagnostics and producers use the same singleton worker.
        Logger::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, logger)

        fun records(): List<JSONObject> = synchronized(sent) { sent.toList() }

        fun diagnostics(): List<JSONObject> = records().filter { it.getString("component") == "app-log-spool" }

        fun pending(): Boolean = directory.listFiles().orEmpty().any { it.extension == "json" }
        try {
            Logger.info("product", "initial operation")
            await { diagnostics().isNotEmpty() && !pending() }
            Thread.sleep(500)
            assertEquals("one real batch and one diagnostic, then delivery settles", 2, records().size)
            assertEquals("error", diagnostics().single().getString("level"))

            Logger.error("product", "independent failure one")
            Logger.error("product", "independent failure two")
            await { records().count { it.getString("component") == "product" } == 3 && !pending() }
            Thread.sleep(500)
            assertEquals(4, records().size)
            assertEquals("independent errors remain separate during the mismatch episode", 1, diagnostics().size)
            assertEquals(
                listOf("independent failure one", "independent failure two"),
                records().filter { it.getString("component") == "product" && it.getString("level") == "error" }
                    .map { it.getString("message") },
            )

            acknowledged.set(true)
            Logger.info("product", "recovery operation")
            await { records().any { it.getString("message") == "recovery operation" } && !pending() }
            acknowledged.set(false)
            Logger.error("product", "new operation failed")
            await { diagnostics().size >= 2 && !pending() }
            Thread.sleep(500)
            assertEquals("a successful ACK starts a fresh mismatch episode", 2, diagnostics().size)
            assertEquals(7, records().size)
            assertTrue(records().any { it.getString("message") == "new operation failed" && it.getString("level") == "error" })
            assertFalse(logger.isPoisoned())
        } finally {
            Logger.shutdown()
            directory.deleteRecursively()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("logger condition did not settle within five seconds", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }
}
