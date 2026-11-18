package ru.voboost.ota

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ProductContractCorpusTest {
    @Test
    fun copiedCorpusHasExactRecordedBytes() {
        File("src/test/resources/product/PRODUCT-CORPUS.sha256").readLines().forEach { line ->
            val expected = line.substringBefore("  ")
            val path = line.substringAfter("  ")
            val file = File(path)
            require(file.isFile) { "Missing corpus file $path" }
            require(OtaHttpTransport.sha256(file.readBytes()) == expected) { "Changed corpus file $path" }
        }
    }

    @Test
    fun sharedProductCorpusMatchesRustAcceptRejectClassification() {
        assertCorpus("channel", ChannelDocument::parse)
        assertCorpus("voboost", ProductManifest::parse)
    }

    @Test
    fun splitRuntimeRequiresDistinctOrderedOwnerAndInjector() {
        val manifest = ProductManifest.parse(File("src/test/resources/product/voboost/valid/minimal.json").readBytes())
        val injector = manifest.artifacts.single { it.file == "voboost-inject" }

        fun document(artifacts: List<ProductArtifact>): ByteArray =
            (
                "{\"schemaVersion\":1,\"releaseIdentity\":${jsonString(manifest.releaseIdentity.source)}," +
                    "\"compatibility\":${manifest.compatibility.canonical()},\"artifacts\":[" +
                    artifacts.joinToString(",", transform = ProductArtifact::canonical) + "]}\n"
            ).toByteArray()

        for (artifacts in listOf(
            manifest.artifacts.filter { it != injector },
            manifest.artifacts.map { if (it == injector) it.copy(role = "daemon") else it },
            manifest.artifacts.filter { it != injector } + injector,
        )) {
            assertThrows(Exception::class.java) { ProductManifest.parse(document(artifacts)) }
        }
    }

    @Test
    fun qnxAgentModulesAreRepeatableAndKeepExactArtifactIdentity() {
        val manifest = ProductManifest.parse(File("src/test/resources/product/voboost/valid/minimal.json").readBytes())
        val font = manifest.artifacts.single { it.file == "libagent-font-cyrillic.so" }

        fun document(added: ProductArtifact): ByteArray =
            (
                "{\"schemaVersion\":1,\"releaseIdentity\":${jsonString(manifest.releaseIdentity.source)}," +
                    "\"compatibility\":${manifest.compatibility.canonical()},\"artifacts\":[" +
                    (manifest.artifacts + added).joinToString(",", transform = ProductArtifact::canonical) + "]}\n"
            ).toByteArray()
        ProductManifest.parse(document(font.copy(file = "libagent-synthetic.so")))
        for (bad in listOf(
            font.copy(file = "libagent-synthetic.so", abi = "arm64-v8a"),
            font.copy(file = "libagent-Synthetic.so"),
            font.copy(file = "libagent-synthetic.so", role = "agent-module"),
        )) {
            assertThrows(Exception::class.java) { ProductManifest.parse(document(bad)) }
        }
    }

    private fun assertCorpus(
        name: String,
        parser: (ByteArray) -> Any,
    ) {
        val root = File("src/test/resources/product/$name")
        root.resolve("valid").walkTopDown().filter { it.isFile && it.extension == "json" }.forEach {
            parser(it.readBytes())
        }
        root.resolve("invalid").walkTopDown().filter { it.isFile && it.extension == "json" }.forEach {
            assertThrows("Expected rejected fixture ${it.name}", Exception::class.java) { parser(it.readBytes()) }
        }
    }
}
