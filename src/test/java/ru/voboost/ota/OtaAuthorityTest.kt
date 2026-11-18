package ru.voboost.ota

import android.system.OsConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.voboost.AppDirectoryAuthority
import ru.voboost.AppFileOps
import ru.voboost.androidPrivateDirectoryOpenFlags
import ru.voboost.platformAppFileOps
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

class OtaAuthorityTest {
    private val transaction = "d".repeat(64)
    private val artifact =
        ProductArtifact(
            "agent-module",
            "agent-settings.dex",
            4,
            OtaHttpTransport.sha256("body".toByteArray()),
            "agent-generation",
            null,
            null,
            null,
            null,
        )

    @Test
    fun androidDirectoryAuthorityKeepsNoFollowWithoutApi28UnsupportedODirectory() {
        val flags = androidPrivateDirectoryOpenFlags()
        assertEquals(
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            flags,
        )
        assertEquals(0, flags and 65_536)
    }

    @Test
    fun hardlinkWrongModeAndForeignOwnerFailBeforeNetwork() =
        runTest {
            val hardlinkRoot = privateRoot("hardlink")
            val victim = File(hardlinkRoot, "victim").apply { writeText("bo") }
            Files.setPosixFilePermissions(victim.toPath(), PosixFilePermissions.fromString("rw-------"))
            Files.createLink(partial(hardlinkRoot).toPath(), victim.toPath())
            assertFailure(hardlinkRoot)

            val modeRoot = privateRoot("mode")
            partial(modeRoot).apply {
                writeText("bo")
                Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-r--r--"))
            }
            assertFailure(modeRoot)

            val ownerRoot = privateRoot("owner")
            partial(ownerRoot).apply {
                writeText("bo")
                Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            val foreignOps =
                object : AppFileOps by platformAppFileOps {
                    override fun openPrivateDirectory(path: File): AppDirectoryAuthority {
                        val delegate = platformAppFileOps.openPrivateDirectory(path)
                        return object : AppDirectoryAuthority by delegate {
                            override fun lstat(name: String) =
                                delegate.lstat(name)?.let { identity ->
                                    if (name.endsWith(".part")) identity.copy(uid = identity.uid + 1) else identity
                                }
                        }
                    }
                }
            assertFailure(ownerRoot, fileOps = foreignOps)
        }

    @Test
    fun parentAndLeafSwapsFailWithoutPublishingSubstitute() =
        runTest {
            val parentRoot = privateRoot("parent")
            val parentOriginal = File(parentRoot.parentFile, "${parentRoot.name}.original")
            val parentHooks =
                object : OtaAuthorityHooks {
                    override fun afterDirectoryOpened(directory: File) {
                        Files.move(directory.toPath(), parentOriginal.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        directory.mkdir()
                    }
                }
            assertFailure(parentRoot, hooks = parentHooks)

            val leafRoot = privateRoot("leaf")
            val leafVictim = File(leafRoot, "victim")
            val leafHooks =
                object : OtaAuthorityHooks {
                    override fun afterPartialOpened(partial: File) {
                        Files.move(partial.toPath(), leafVictim.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        partial.writeText("foreign")
                    }
                }
            assertFailure(leafRoot, hooks = leafHooks)
            assertTrue(partial(leafRoot).readText() == "foreign")
            assertTrue(!File(leafRoot, "$transaction--${artifact.file}").exists())
        }

    private suspend fun assertFailure(
        root: File,
        fileOps: AppFileOps = platformAppFileOps,
        hooks: OtaAuthorityHooks = OtaAuthorityHooks.NONE,
    ) {
        val result =
            runCatching {
                OtaHttpTransport(
                    allowLoopbackHttp = true,
                    fileOps = fileOps,
                    authorityHooks = hooks,
                ).download(URI("http://127.0.0.1/release/voboost.json"), artifact, root, transaction)
            }
        assertTrue(result.isFailure)
    }

    private fun privateRoot(label: String): File =
        Files.createTempDirectory("voboost-ota-authority-$label").toFile().also {
            platformAppFileOps.openPrivateDirectory(it).close()
        }

    private fun partial(root: File) = File(root, "$transaction--${artifact.file}.part")
}
