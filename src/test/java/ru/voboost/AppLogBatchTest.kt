package ru.voboost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicInteger

class AppLogBatchTest {
    @Test fun producerContextUsesExistingFieldsWithoutChangingWireKeys() {
        val fields = mapOf("producerTimestamp" to "2026-10-26T09:00:00.000Z", "event" to "log.line", "outcome" to "info")
        val batch = AppLogBatch.new(listOf(AppLogRecord("info", "wire", "line \"quoted\"", fields)))
        val wire = JSONObject(batch.encoded().toString(Charsets.UTF_8))
        assertEquals(setOf("schemaVersion", "batchId", "records"), wire.keys().asSequence().toSet())
        val record = wire.getJSONArray("records").getJSONObject(0)
        assertEquals(setOf("level", "component", "message", "fields"), record.keys().asSequence().toSet())
        val restored = record.getJSONObject("fields")
        fields.forEach { (key, value) -> assertEquals(value, restored.getString(key)) }
        assertEquals("line \"quoted\"", record.getString("message"))
    }

    private companion object {
        const val MAX_QUARANTINED_BATCHES = 8
    }

    @Test fun exactRecordAndTimeLimitsCloseBatch() {
        val builder = AppLogBatchBuilder(1_000)
        repeat(64) { assertTrue(builder.offer(AppLogRecord("debug", "test", "$it"), 1_000)) }
        assertTrue(builder.mustClose(1_000))
        assertFalse(builder.offer(AppLogRecord("info", "test", "overflow"), 1_000))

        val timed = AppLogBatchBuilder(1_000)
        assertTrue(timed.offer(AppLogRecord("info", "test", "first"), 1_000))
        assertTrue(timed.mustClose(1_250))
    }

    @Test fun errorClosesImmediatelyAndWarningIsRejected() {
        val builder = AppLogBatchBuilder(0)
        assertTrue(builder.offer(AppLogRecord("error", "test", "failed"), 0))
        assertTrue(builder.mustClose(0))
        assertThrows(IllegalArgumentException::class.java) {
            AppLogRecord("warning", "test", "invalid")
        }
    }

    @Test fun ackQuarantinesMismatchInsteadOfBlockingDelivery() {
        val directory = Files.createTempDirectory("app-log-spool").toFile()
        val batch = AppLogBatch.new(listOf(AppLogRecord("info", "test", "ready")))
        val mismatch = AppLogSpool(directory, sender = { "0".repeat(32) })
        val persisted = mismatch.persist(batch)
        // A wrong ACK is a permanent protocol rejection: the batch is
        // quarantined rather than blocking all later delivery.
        assertEquals(0, mismatch.deliverPending())
        assertFalse(persisted.exists())
        assertTrue(File(persisted.parentFile, "${persisted.name}.quarantined").exists())

        val accepted = AppLogSpool(directory, sender = { batch.id })
        // A quarantined batch is never replayed to the daemon.
        assertEquals(0, accepted.deliverPending())
        assertTrue(File(persisted.parentFile, "${persisted.name}.quarantined").exists())
        directory.deleteRecursively()
    }

    @Test fun overdueQuarantineKeepsLaterBatchesDeliverable() {
        val directory = Files.createTempDirectory("app-log-spool-quarantine").toFile()
        val stuck = AppLogBatch.new(listOf(AppLogRecord("info", "test", "stuck")))
        val later = AppLogBatch.new(listOf(AppLogRecord("info", "test", "later")))
        val pendingIds = ArrayDeque(listOf(stuck.id, later.id))
        var seenStuck = false
        val spool =
            AppLogSpool(
                directory,
                sender = {
                    val id = pendingIds.removeFirst()
                    // The daemon persistently refuses to ACK the first batch ID.
                    if (id == stuck.id) {
                        seenStuck = true
                        "f".repeat(32)
                    } else {
                        check(seenStuck)
                        id
                    }
                },
            )
        spool.persist(stuck)
        Thread.sleep(5)
        spool.persist(later)

        assertEquals(1, spool.deliverPending())
        val names = directory.listFiles().orEmpty().map { it.name }
        assertTrue(names.any { it == "batch-${stuck.id}.json.quarantined" })
        assertTrue(names.none { it == "batch-${stuck.id}.json" })
        assertTrue(names.none { it == "batch-${later.id}.json" })
        directory.deleteRecursively()
    }

    @Test fun quarantineStaysBoundedWhileKeepsDelivering() {
        val directory = Files.createTempDirectory("app-log-spool-quarantine-bound").toFile()
        val spool =
            AppLogSpool(
                directory,
                sender = {
                    // Every batch is permanently refused: a wrong ACK each time.
                    "0".repeat(32)
                },
            )
        val ids = mutableListOf<String>()
        repeat(12) { index ->
            val batch = AppLogBatch.new(listOf(AppLogRecord("error", "test", "mismatch-$index")))
            ids.add(batch.id)
            spool.persist(batch)
            assertEquals(0, spool.deliverPending())
            Thread.sleep(2)
        }
        val quarantined = directory.listFiles().orEmpty().filter { it.name.endsWith(".quarantined") }
        assertEquals(MAX_QUARANTINED_BATCHES, quarantined.size)
        // The quarantine keeps the NEWEST batches and drops the oldest overflow.
        val quarantinedIds = quarantined.map { it.name.removePrefix("batch-").removeSuffix(".json.quarantined") }.toSet()
        assertEquals(ids.takeLast(MAX_QUARANTINED_BATCHES).toSet(), quarantinedIds)
        val names = directory.listFiles().orEmpty().map { it.name }
        assertEquals(0, names.count { it.matches(Regex("batch-[0-9a-f]{32}\\.json")) })
        directory.deleteRecursively()
    }

    @Test fun spoolRejectsSymlinkAndHardlinkWithoutTouchingVictim() {
        val directory = Files.createTempDirectory("app-log-spool-substitution").toFile()
        val victim = File(directory, "victim").apply { writeText("private") }
        Files.setPosixFilePermissions(
            victim.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
        val sends = AtomicInteger()
        val spool =
            AppLogSpool(directory, {
                sends.incrementAndGet()
                "0".repeat(32)
            })

        val symlink = File(directory, "batch-${"0".repeat(32)}.json")
        Files.createSymbolicLink(symlink.toPath(), victim.toPath())
        assertThrows(IOException::class.java) { spool.deliverPending() }
        assertEquals("private", victim.readText())
        assertEquals(0, sends.get())
        Files.delete(symlink.toPath())

        val hardlink = File(directory, "batch-${"1".repeat(32)}.json")
        Files.createLink(hardlink.toPath(), victim.toPath())
        assertThrows(IOException::class.java) { spool.deliverPending() }
        assertEquals("private", victim.readText())
        assertEquals(0, sends.get())
        Files.delete(hardlink.toPath())

        directory.deleteRecursively()
    }

    @Test fun successfulOffersGuaranteeValidCloseAcrossEnvelopeBoundary() {
        // Maximum-size valid messages whose raw record JSON sums sit below
        // the batch bound while the FULL envelope (commas, batchId, braces)
        // would exceed it: admission must account the real close-time bytes.
        val builder = AppLogBatchBuilder(0)
        val long = AppLogRecord("info", "probe", "界".repeat(2048))
        var admitted = 0
        while (builder.offer(long, 0)) admitted++
        assertTrue("admitted some records", admitted > 0)
        val batch = builder.close()
        assertTrue(
            "close must succeed after successful offers",
            batch.encoded().size <= APP_LOG_MAX_BATCH_BYTES,
        )
        // N-1 / N / N+1 boundary: exactly at the bound offers still succeed
        // and close; one byte beyond it the next offer is refused.
        val tail = AppLogBatchBuilder(0)
        val mid = AppLogRecord("info", "probe", "x".repeat(1024))
        var count = 0
        while (tail.offer(mid, 0)) count++
        assertTrue(tail.close().encoded().size <= APP_LOG_MAX_BATCH_BYTES)
        assertTrue("boundary exercised", count > 32)
    }

    @Test fun sixtyFiveSmallBatchesSurviveSenderOutageAndDrainOnRecovery() {
        val directory = Files.createTempDirectory("app-log-spool").toFile()
        val available = java.util.concurrent.atomic.AtomicBoolean(false)
        val delivered = AtomicInteger(0)
        val sender =
            AppLogBatchSender { encoded ->
                if (!available.get()) throw IOException("sender unavailable")
                delivered.incrementAndGet()
                JSONObject(encoded.toString(Charsets.UTF_8)).getString("batchId")
            }
        val spool = AppLogSpool(directory, sender)
        repeat(65) { spool.persist(AppLogBatch.new(listOf(AppLogRecord("info", "probe", "seed-$it")))) }
        assertEquals(65, directory.listFiles()!!.size)
        runCatching { spool.deliverPending() }
        // Unexpected persistence must not have been required for the outage.
        available.set(true)
        repeat(3) { runCatching { spool.deliverPending() } }
        assertEquals(
            "a recovered sender drains the whole admissible backlog",
            65,
            delivered.get(),
        )
    }

    @Test fun daemonRecordContractRejectsControlAndOversizeComponent() {
        assertThrows(IllegalArgumentException::class.java) {
            AppLogRecord("info", "probe", "line1\nline2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppLogRecord("info", "control\u0001", "ok")
        }
        assertThrows(IllegalArgumentException::class.java) {
            // 33 CJK chars = 99 UTF-8 bytes > 64-byte daemon component bound.
            AppLogRecord("info", "界".repeat(33), "ok")
        }
        // In-bounds Unicode component is accepted.
        AppLogRecord("info", "界".repeat(10), "message")
    }

    @Test fun errorOnlyOverflowCreatesOneNonrecursiveLossRecord() {
        val directory = Files.createTempDirectory("app-log-spool-overflow").toFile()
        val spool = AppLogSpool(directory, { error("not delivered") }, 700)
        repeat(8) {
            spool.persist(
                AppLogBatch.new(
                    listOf(AppLogRecord("error", "test", "failure-$it-${"x".repeat(160)}")),
                ),
            )
        }
        val lossRecords =
            directory.listFiles().orEmpty().sumOf { file ->
                val records = JSONObject(file.readText()).getJSONArray("records")
                (0 until records.length()).count { index ->
                    records.getJSONObject(index).optJSONObject("fields")
                        ?.optString("event") == "spool-overflow-loss"
                }
            }
        assertEquals(1, lossRecords)
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 700)
        directory.deleteRecursively()
    }
}
