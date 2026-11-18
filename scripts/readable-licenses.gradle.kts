import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Original upstream notices retain their own licensing terms.
val licenseHashes = mapOf(
    "android-jvm-NOTICES.txt" to "91fdca4183a059ac2811dbb338f001caf7e0badee6fadf6ff3eb7596bf6c0602",
    "rust-standard-library-NOTICES.html" to "68129500b616d5838629e68f55ff3aed5e096dacf60ce9eb41bbe599a563afa6",
    "native-runtime-NOTICES.txt" to "db98806e173362991c8e6b26594fb5c7a4b34e3f6858d77be2bb7f36b47114ef",
    "LICENSE" to "1ad31a0a4fbab5c2e220a1ed351e48d6803a62798b492f8f7fd721d85b96cf84",
    "LICENSE.ru.md" to "3ef383ba58ddfea59af2dfe34d1607744300ea800b76d7b2996a7c9a71cdb24e",
    "dobby-LICENSE.txt" to "43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1",
    "libsepol-COPYING.txt" to "6095e9ffa777dd22839f7801aa845b31c9ed07f3d6bf8a26dc5d2dec8ccc0ef3",
    "lsplant-LICENSE.txt" to "09acdfb5bfb7aaed93d7d7fa5cebfc7350c555847d2ed07b5b9716da43670e04",
    "native-rust-NOTICES.txt" to "3e319627dead37a54a95e91e1e0fd6cd157aae5c1ccba310124d36799c08fc76",
    "yyjson-LICENSE.txt" to "45e384d3d52c73cba3a64d6e6c25d47cd738cd8a55c30629e3201046eda62947",
)
val licenseDirectory = file("src/main/license-assets/licenses")
val courierLicenses = licenseHashes.filterKeys {
    it == "LICENSE" || it == "LICENSE.ru.md" || it == "android-jvm-NOTICES.txt"
}
fun licenseDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun verifyLicenseAssets(apk: File) {
    ZipFile(apk).use { zip ->
        val entries = zip.entries().asSequence().filter { it.name.startsWith("assets/licenses/") }.toList()
        require(entries.map { it.name }.sorted() == courierLicenses.keys.map { "assets/licenses/$it" }.sorted()) {
            "APK readable license inventory differs"
        }
        for ((name, expected) in courierLicenses) {
            val entry = requireNotNull(zip.getEntry("assets/licenses/$name"))
            require(!entry.isDirectory && entry.size in 1..2_000_000) { "Invalid readable license $name" }
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            require(licenseDigest(bytes) == expected) { "APK readable license differs: $name" }
        }
    }
}
tasks.register("verifyLicenseSources") {
    group = "verification"
    inputs.dir(licenseDirectory)
    doLast {
        require(licenseDirectory.listFiles()?.map { it.name }?.sorted() == licenseHashes.keys.sorted()) {
            "Readable license source inventory differs"
        }
        for ((name, expected) in licenseHashes) {
            val source = licenseDirectory.resolve(name)
            require(!java.nio.file.Files.isSymbolicLink(source.toPath()) && source.isFile &&
                licenseDigest(source.readBytes()) == expected) { "Readable license source differs: $name" }
        }
    }
}
tasks.register("verifyPackagedLicenses") {
    group = "verification"
    dependsOn("copyReleaseApk", "verifyLicenseSources")
    val apk = layout.buildDirectory.file("outputs/apk/release/voboost-app.apk")
    inputs.file(apk)
    doLast { verifyLicenseAssets(apk.get().asFile) }
}
tasks.register("testLicenseContract") {
    group = "verification"
    dependsOn("verifyLicenseSources")
    doLast {
        val directory = layout.buildDirectory.dir("license-contract-test").get().asFile
        directory.mkdirs()
        val fixture = directory.resolve("fixture.apk")
        fun writeFixture(missing: String? = null, changed: String? = null, extra: String? = null) {
            ZipOutputStream(fixture.outputStream()).use { zip ->
                for (name in courierLicenses.keys) {
                    if (name == missing) continue
                    zip.putNextEntry(ZipEntry("assets/licenses/$name"))
                    zip.write(if (name == changed) "changed license".toByteArray() else licenseDirectory.resolve(name).readBytes())
                    zip.closeEntry()
                }
                if (extra != null) {
                    zip.putNextEntry(ZipEntry("assets/licenses/$extra"))
                    zip.write("undeclared".toByteArray())
                    zip.closeEntry()
                }
            }
        }
        fun rejects(action: () -> Unit) {
            var rejected = false
            try { action() } catch (_: IllegalArgumentException) { rejected = true }
            check(rejected) { "License mutation was accepted" }
        }
        writeFixture()
        verifyLicenseAssets(fixture)
        for (name in courierLicenses.keys) {
            writeFixture(missing = name)
            rejects { verifyLicenseAssets(fixture) }
            writeFixture(changed = name)
            rejects { verifyLicenseAssets(fixture) }
        }
        val excluded = licenseHashes.keys - courierLicenses.keys
        for (name in excluded + "extra.txt") {
            writeFixture(extra = name)
            rejects { verifyLicenseAssets(fixture) }
        }
        logger.lifecycle("Readable APK license contract: 1 valid, ${courierLicenses.size * 2 + excluded.size + 1} rejected mutations")
    }
}
