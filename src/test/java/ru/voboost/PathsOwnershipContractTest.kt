package ru.voboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PathsOwnershipContractTest {
    @Test
    fun allAppPathsRemainUnderTheAppZone() {
        val root = Files.createTempDirectory("voboost-paths").toFile()
        val app = File(root, "app")
        val paths = Paths(app)
        assertEquals(app.canonicalFile, paths.appZone.canonicalFile)
        assertEquals(File(app, "logs").canonicalFile, paths.logsDir.canonicalFile)
        assertEquals(File(app, "ota").canonicalFile, paths.otaDir.canonicalFile)
        assertEquals(File(app, "ota-transaction").canonicalFile, paths.otaTransactionFile.canonicalFile)
        assertTrue(paths.logsDir.toPath().startsWith(app.toPath()))
        assertTrue(paths.otaDir.toPath().startsWith(app.toPath()))
    }
}
