package ru.voboost.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OtaTransportTest {
    @Test
    fun resumesMatchingRangeAndVerifiesFinalIdentity() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val complete = "verified-artifact".toByteArray()
                val prefix = complete.copyOfRange(0, 8)
                val suffix = complete.copyOfRange(8, complete.size)
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 8-${complete.lastIndex}/${complete.size}")
                        .setBody(okio.Buffer().write(suffix)),
                )
                val directory = Files.createTempDirectory("voboost-ota").toFile()
                val transaction = "a".repeat(64)
                val artifact =
                    ProductArtifact(
                        "agent-module",
                        "agent-settings.dex",
                        complete.size.toLong(),
                        OtaHttpTransport.sha256(complete),
                        "agent-generation",
                        null,
                        null,
                        null,
                        null,
                    )
                writePrivatePartial(File(directory, "$transaction--${artifact.file}.part"), prefix)
                val result =
                    OtaHttpTransport(allowLoopbackHttp = true).download(
                        server.url("/release/voboost.json").toUri(),
                        artifact,
                        directory,
                        transaction,
                    )

                assertArrayEquals(complete, result.readBytes())
                assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun redirectsAndDocumentBytesRemainBounded() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                repeat(3) { index ->
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", "/redirect-${index + 1}"),
                    )
                }
                val transport = OtaHttpTransport(allowLoopbackHttp = true)
                assertTrue(runCatching { transport.fetch(server.url("/start").toUri(), 64) }.isFailure)

                server.enqueue(MockResponse().setBody("12345"))
                assertTrue(runCatching { transport.fetch(server.url("/oversize").toUri(), 4) }.isFailure)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun fullResponseRestartsAnExistingPartialFile() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val complete = "replacement".toByteArray()
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(complete)))
                val directory = Files.createTempDirectory("voboost-ota-restart").toFile()
                val transaction = "b".repeat(64)
                val artifact =
                    ProductArtifact(
                        "agent-module",
                        "agent-settings.dex",
                        complete.size.toLong(),
                        OtaHttpTransport.sha256(complete),
                        "agent-generation",
                        null,
                        null,
                        null,
                        null,
                    )
                writePrivatePartial(File(directory, "$transaction--${artifact.file}.part"), "stale".toByteArray())

                val result =
                    OtaHttpTransport(allowLoopbackHttp = true).download(
                        server.url("/release/voboost.json").toUri(),
                        artifact,
                        directory,
                        transaction,
                    )

                assertArrayEquals(complete, result.readBytes())
                assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun crossOriginIdnAndAlternatePortAreRejected() =
        runTest {
            val first = MockWebServer()
            val second = MockWebServer()
            first.start()
            second.start()
            try {
                first.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", second.url("/foreign")),
                )
                val transport = OtaHttpTransport(allowLoopbackHttp = true)
                assertTrue(runCatching { transport.fetch(first.url("/start").toUri(), 64) }.isFailure)
                assertTrue(runCatching { parseHttpsUrl("https://xn--e1afmkfd.example/voboost.json") }.isFailure)
                assertTrue(runCatching { parseHttpsUrl("https://releases.voboost.ru:444/voboost.json") }.isFailure)
            } finally {
                first.shutdown()
                second.shutdown()
            }
        }

    @Test
    fun cancellationCancelsStalledHeadersAndBodyCalls() =
        runTest {
            supervisorScope {
                val server = MockWebServer()
                server.start()
                try {
                    val factory = CountingCallFactory()
                    val transport = OtaHttpTransport(factory, allowLoopbackHttp = true)
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val headers = async(Dispatchers.IO) { transport.fetch(server.url("/headers").toUri(), 64) }
                    requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    headers.cancel()
                    runCatching { headers.await() }
                    assertEquals(1, factory.cancelled.get())

                    server.enqueue(MockResponse().setBody("slow-body").throttleBody(1, 10, TimeUnit.SECONDS))
                    val body = async(Dispatchers.IO) { transport.fetch(server.url("/body").toUri(), 64) }
                    requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    body.cancel()
                    runCatching { body.await() }
                    assertEquals(2, factory.cancelled.get())
                } finally {
                    runCatching { server.shutdown() }
                }
            }
        }

    @Test
    fun cancellationCancelsRedirectAndResumeCalls() =
        runTest {
            supervisorScope {
                val server = MockWebServer()
                server.start()
                try {
                    val factory = CountingCallFactory()
                    val transport = OtaHttpTransport(factory, allowLoopbackHttp = true)
                    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/stalled"))
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val redirected = async(Dispatchers.IO) { transport.fetch(server.url("/redirect").toUri(), 64) }
                    requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    redirected.cancel()
                    runCatching { redirected.await() }
                    assertEquals(1, factory.cancelled.get())

                    val complete = "resume-body".toByteArray()
                    val directory = Files.createTempDirectory("voboost-ota-cancel-resume").toFile()
                    val transaction = "c".repeat(64)
                    val artifact =
                        ProductArtifact(
                            "agent-module",
                            "agent-settings.dex",
                            complete.size.toLong(),
                            OtaHttpTransport.sha256(complete),
                            "agent-generation",
                            null,
                            null,
                            null,
                            null,
                        )
                    writePrivatePartial(
                        File(directory, "$transaction--${artifact.file}.part"),
                        complete.copyOfRange(0, 2),
                    )
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes 2-${complete.lastIndex}/${complete.size}")
                            .setBody(okio.Buffer().write(complete.copyOfRange(2, complete.size)))
                            .throttleBody(1, 10, TimeUnit.SECONDS),
                    )
                    val resumed =
                        async(Dispatchers.IO) {
                            transport.download(server.url("/release/voboost.json").toUri(), artifact, directory, transaction)
                        }
                    requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    resumed.cancel()
                    runCatching { resumed.await() }
                    assertEquals(2, factory.cancelled.get())
                } finally {
                    runCatching { server.shutdown() }
                }
            }
        }

    private class CountingCallFactory : Call.Factory {
        private val delegate = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val cancelled = AtomicInteger()

        override fun newCall(request: Request): Call {
            val call = delegate.newCall(request)
            return object : Call by call {
                override fun cancel() {
                    cancelled.incrementAndGet()
                    call.cancel()
                }
            }
        }
    }

    @Test
    fun sealedPartialFromCutBeforeRenameResumesAndPublishes() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val complete = "sealed-resume-artifact".toByteArray()
                val directory = Files.createTempDirectory("voboost-ota").toFile()
                val transaction = "b".repeat(64)
                val artifact =
                    ProductArtifact(
                        "agent-module",
                        "agent-settings.dex",
                        complete.size.toLong(),
                        OtaHttpTransport.sha256(complete),
                        "agent-generation",
                        null,
                        null,
                        null,
                        null,
                    )
                // A previous run fully downloaded, hash-verified and sealed the
                // partial, but was cut between the chmod/fsync and the rename.
                val partial = File(directory, "$transaction--${artifact.file}.part")
                partial.writeBytes(complete)
                Files.setPosixFilePermissions(
                    partial.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("r--------"),
                )
                val result =
                    OtaHttpTransport(allowLoopbackHttp = true).download(
                        server.url("/release/voboost.json").toUri(),
                        artifact,
                        directory,
                        transaction,
                    )
                assertArrayEquals(complete, result.readBytes())
                assertEquals("no network needed for sealed resume", 0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    private fun writePrivatePartial(
        file: File,
        bytes: ByteArray,
    ) {
        file.writeBytes(bytes)
        Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    }
}
