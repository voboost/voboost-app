package ru.voboost.ota

import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RunWith(RobolectricTestRunner::class)
class DescriptorBoundApkProviderTest {
    private lateinit var directory: File
    private var nowMillis = 1_000L
    private var tokenSequence = 0

    @Before
    fun setup() {
        directory =
            File.createTempFile("descriptor-apk-provider-", "").apply {
                delete()
                mkdirs()
            }
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun delayedOpenKeepsTheOriginallyIssuedLeaseAfterPathReplacement() {
        val path = File(directory, "voboost-update.apk").apply { writeText("verified-old") }
        val registry = registry()
        val uri = registry.issue(open(path), INSTALLER_UID, path.length())

        val replacement = File(directory, "replacement.apk").apply { writeText("new-release") }
        Files.move(
            replacement.toPath(),
            path.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )

        assertEquals("new-release", path.readText())
        assertEquals("verified-old", consumeText(registry, uri, INSTALLER_UID))
        assertNull(registry.consume(uri, INSTALLER_UID))
    }

    @Test
    fun productionAdapterDuplicatesTheExactOpenedDescriptorOnceWithoutAPath() {
        val original = Mockito.mock(ParcelFileDescriptor::class.java)
        val duplicate = Mockito.mock(ParcelFileDescriptor::class.java)
        val exactOpenedDescriptor = FileDescriptor()
        Mockito.`when`(original.fileDescriptor).thenReturn(exactOpenedDescriptor)

        Mockito.mockStatic(ParcelFileDescriptor::class.java).use { staticMock ->
            staticMock.`when`<ParcelFileDescriptor> {
                ParcelFileDescriptor.dup(exactOpenedDescriptor)
            }.thenReturn(duplicate)
            val retained = ParcelApkGrantHandle(original)

            val issued = retained.duplicate()
            retained.close()

            staticMock.verify(
                { ParcelFileDescriptor.dup(exactOpenedDescriptor) },
                Mockito.times(1),
            )
            Mockito.verify(original, Mockito.times(1)).close()
            assertSame(duplicate, issued.detachParcelFileDescriptor())
            issued.close()
        }
    }

    @Test
    fun concurrentRestageCannotChangeEitherOutstandingDescriptor() {
        val path = File(directory, "voboost-update.apk").apply { writeText("first") }
        val registry = registry()
        val first = registry.issue(open(path), INSTALLER_UID, path.length())

        val replacement = File(directory, "next.apk").apply { writeText("second") }
        Files.move(
            replacement.toPath(),
            path.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val second = registry.issue(open(path), INSTALLER_UID, path.length())

        assertEquals("first", consumeText(registry, first, INSTALLER_UID))
        assertEquals("second", consumeText(registry, second, INSTALLER_UID))
        assertEquals(0, registry.activeGrantCount())
    }

    @Test
    fun callerBindingReplayAndUriTamperingFailClosed() {
        val path = File(directory, "app.apk").apply { writeText("signed") }
        val registry = registry()
        val uri = registry.issue(open(path), INSTALLER_UID, path.length())

        assertNull(registry.consume(uri, INSTALLER_UID + 1))
        assertEquals("signed", consumeText(registry, uri, INSTALLER_UID))
        assertNull(registry.consume(uri, INSTALLER_UID))
        assertNull(
            registry.consume(
                uri.buildUpon().appendQueryParameter("again", "1").build(),
                INSTALLER_UID,
            ),
        )
    }

    @Test
    fun expiryCancellationAndProcessRestartReleaseAuthority() {
        val expiryRegistry = registry()
        val expiring = fileGrant(expiryRegistry, "expiry")
        nowMillis += TTL_MILLIS
        assertNull(expiryRegistry.metadata(expiring, INSTALLER_UID))
        assertEquals(0, expiryRegistry.activeGrantCount())

        val cancellationRegistry = registry()
        val cancelled = fileGrant(cancellationRegistry, "cancel")
        cancellationRegistry.revoke(cancelled)
        assertNull(cancellationRegistry.consume(cancelled, INSTALLER_UID))
        assertEquals(0, cancellationRegistry.activeGrantCount())

        val dyingRegistry = registry()
        val fromDeadProcess = fileGrant(dyingRegistry, "death")
        dyingRegistry.closeAll()
        val restartedRegistry = registry()
        assertNull(restartedRegistry.consume(fromDeadProcess, INSTALLER_UID))
        assertEquals(0, restartedRegistry.activeGrantCount())
    }

    @Test
    fun fdCountAndRetainedBytesAreStrictlyBounded() {
        val registry = registry(maxBytes = 10)
        val first = descriptor("1234")
        val second = descriptor("5678")
        val rejectedByCount = descriptor("x")
        registry.issue(first, INSTALLER_UID, 4)
        registry.issue(second, INSTALLER_UID, 4)
        try {
            assertThrows(IllegalStateException::class.java) {
                registry.issue(rejectedByCount, INSTALLER_UID, 1)
            }
        } finally {
            rejectedByCount.close()
        }
        assertEquals(2, registry.activeGrantCount())
        registry.closeAll()

        val byteRegistry = registry(maxGrants = 3, maxBytes = 5)
        val accepted = descriptor("1234")
        val rejectedByBytes = descriptor("56")
        byteRegistry.issue(accepted, INSTALLER_UID, 4)
        try {
            assertThrows(IllegalStateException::class.java) {
                byteRegistry.issue(rejectedByBytes, INSTALLER_UID, 2)
            }
        } finally {
            rejectedByBytes.close()
            byteRegistry.closeAll()
        }
    }

    @Test
    fun canonicalUriHasNoPathOrTreeGrantSurface() {
        val registry = registry()
        val uri = fileGrant(registry, "uri")

        assertEquals("content", uri.scheme)
        assertEquals(DescriptorBoundApkProvider.AUTHORITY, uri.authority)
        assertEquals("grant", uri.pathSegments[0])
        assertEquals(2, uri.pathSegments.size)
        assertTrue(uri.pathSegments[1].matches(Regex("^[0-9a-f]{32}\\.apk$")))
        assertFalse(uri.toString().contains("voboost-update.apk"))
        registry.closeAll()
    }

    private fun registry(
        maxGrants: Int = 2,
        maxBytes: Long = 1024,
    ): OneShotApkGrantRegistry =
        OneShotApkGrantRegistry(
            authority = DescriptorBoundApkProvider.AUTHORITY,
            nowMillis = { nowMillis },
            tokenProvider = {
                tokenSequence++
                tokenSequence.toString(16).padStart(32, '0')
            },
            ttlMillis = TTL_MILLIS,
            maxActiveGrants = maxGrants,
            maxActiveBytes = maxBytes,
        )

    private fun fileGrant(
        registry: OneShotApkGrantRegistry,
        content: String,
    ): android.net.Uri {
        val path = File(directory, "$content.apk").apply { writeText(content) }
        return registry.issue(open(path), INSTALLER_UID, path.length())
    }

    private fun descriptor(content: String): TestGrantHandle = TestGrantHandle(content.toByteArray())

    private fun open(path: File): TestGrantHandle = TestGrantHandle(path.readBytes())

    private fun consumeText(
        registry: OneShotApkGrantRegistry,
        uri: android.net.Uri,
        callerUid: Int,
    ): String {
        val descriptor = requireNotNull(registry.consume(uri, callerUid)) as TestGrantHandle
        return descriptor.use { it.bytes.toString(Charsets.UTF_8) }
    }

    private class TestGrantHandle(val bytes: ByteArray) : ApkGrantHandle {
        private var closed = false

        override fun duplicate(): ApkGrantHandle {
            check(!closed)
            return TestGrantHandle(bytes.copyOf())
        }

        override fun detachParcelFileDescriptor(): ParcelFileDescriptor = error("Host test handles are never detached into Android")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val INSTALLER_UID = 1000
        const val TTL_MILLIS = 5_000L
    }
}
