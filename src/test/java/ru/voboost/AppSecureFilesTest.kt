package ru.voboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

class AppSecureFilesTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "app-files-${UUID.randomUUID()}")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun tempSubstitutionFailsWithoutDeletingSubstituteOrVictim() {
        val target = File(root, "state.json")
        val victim = File(root, "victim").apply { writeText("victim") }
        assertThrows(IOException::class.java) {
            AppSecureFiles.writeAtomically(
                target,
                "state".toByteArray(),
                hooks =
                    object : AppFileMutationHooks {
                        override fun afterTempCreated(
                            tempName: String,
                            targetName: String,
                        ) {
                            Files.delete(File(root, tempName).toPath())
                            Files.createSymbolicLink(File(root, tempName).toPath(), victim.toPath())
                        }
                    },
            )
        }
        assertEquals("victim", victim.readText())
        assertTrue(root.listFiles().orEmpty().any { Files.isSymbolicLink(it.toPath()) })
    }

    @Test
    fun targetAndDirectorySwapsFailClosed() {
        val target = File(root, "state.json")
        AppSecureFiles.writeAtomically(target, "old".toByteArray())
        val victim = File(root, "victim").apply { writeText("victim") }
        assertThrows(IOException::class.java) {
            AppSecureFiles.writeAtomically(
                target,
                "new".toByteArray(),
                hooks =
                    object : AppFileMutationHooks {
                        override fun beforePublish(
                            tempName: String,
                            targetName: String,
                        ) {
                            Files.delete(target.toPath())
                            Files.createSymbolicLink(target.toPath(), victim.toPath())
                        }
                    },
            )
        }
        assertEquals("victim", victim.readText())

        Files.delete(target.toPath())
        AppSecureFiles.writeAtomically(target, "old-again".toByteArray())

        val held = File(root.parentFile, "${root.name}-held")
        assertThrows(IOException::class.java) {
            AppSecureFiles.writeAtomically(
                target,
                "newer".toByteArray(),
                hooks =
                    object : AppFileMutationHooks {
                        override fun afterTempCreated(
                            tempName: String,
                            targetName: String,
                        ) {
                            Files.move(root.toPath(), held.toPath())
                            root.mkdirs()
                        }
                    },
            )
        }
        held.deleteRecursively()
    }

    @Test
    fun hardlinksSymlinksAndClearSubstitutionAreNeverFollowedOrDeleted() {
        val target = File(root, "state.json")
        val victim = File(root, "victim").apply { writeText("victim") }
        Files.createLink(target.toPath(), victim.toPath())
        assertThrows(IOException::class.java) {
            AppSecureFiles.writeAtomically(target, "state".toByteArray())
        }
        assertEquals("victim", victim.readText())
        Files.delete(target.toPath())

        AppSecureFiles.writeAtomically(target, "owned".toByteArray())
        assertThrows(IOException::class.java) {
            AppSecureFiles.clearExact(
                target,
                hooks =
                    object : AppFileMutationHooks {
                        override fun beforePublish(
                            tempName: String,
                            targetName: String,
                        ) {
                            Files.delete(target.toPath())
                            Files.createSymbolicLink(target.toPath(), victim.toPath())
                        }
                    },
            )
        }
        assertEquals("victim", victim.readText())
        assertTrue(Files.isSymbolicLink(target.toPath()))
    }

    @Test
    fun `owned stale temp is reconciled and directory publication remains usable`() {
        val stale = File(root, "state.json.0123456789abcdef0123456789abcdef.tmp")
        stale.writeText("stale")
        Files.setPosixFilePermissions(
            stale.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )

        AppSecureFiles.writeAtomically(File(root, "state.json"), "fresh".toByteArray())

        assertFalse(stale.exists())
        assertEquals("fresh", File(root, "state.json").readText())
    }

    @Test
    fun `stale temp symlink hardlink and read-only node fail closed`() {
        val target = File(root, "state.json")
        val victim = File(root, "victim").apply { writeText("keep") }
        val stale = File(root, "state.json.0123456789abcdef0123456789abcdef.tmp")
        Files.createSymbolicLink(stale.toPath(), victim.toPath())
        assertThrows(IOException::class.java) {
            AppSecureFiles.writeAtomically(target, "fresh".toByteArray())
        }
        assertEquals("keep", victim.readText())
        Files.delete(stale.toPath())

        Files.createLink(stale.toPath(), victim.toPath())
        assertThrows(IOException::class.java) {
            AppSecureFiles.writeAtomically(target, "fresh".toByteArray())
        }
        assertEquals("keep", victim.readText())
        Files.delete(stale.toPath())

        stale.writeText("readonly")
        Files.setPosixFilePermissions(stale.toPath(), setOf(PosixFilePermission.OWNER_READ))
        assertThrows(IOException::class.java) {
            AppSecureFiles.writeAtomically(target, "fresh".toByteArray())
        }
        assertEquals("readonly", stale.readText())
    }
}
