package ru.voboost.ota

import org.json.JSONObject
import java.net.URI

internal class ProductContractException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class ProductSemVer(
    val major: ULong,
    val minor: ULong,
    val patch: ULong,
    val prerelease: List<String>,
    val build: List<String>,
    val source: String,
) : Comparable<ProductSemVer> {
    override fun compareTo(other: ProductSemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return compareValues(prerelease.isEmpty(), other.prerelease.isEmpty())
        }
        for (index in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            val leftNumber = left.all { it in '0'..'9' }
            val rightNumber = right.all { it in '0'..'9' }
            val compared =
                when {
                    leftNumber && rightNumber -> compareValues(left.length, right.length).takeIf { it != 0 } ?: left.compareTo(right)
                    leftNumber -> -1
                    rightNumber -> 1
                    else -> left.compareTo(right)
                }
            if (compared != 0) return compared
        }
        return 0
    }

    companion object {
        private val pattern =
            Regex(
                "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                    "(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)" +
                    "(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?" +
                    "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
            )

        fun parse(value: String): ProductSemVer {
            requireContract(value.length <= 128, "releaseIdentity is too long")
            val match = pattern.matchEntire(value) ?: throw ProductContractException("Invalid SemVer")

            fun component(index: Int): ULong =
                match.groupValues[index].toULongOrNull()
                    ?: throw ProductContractException("SemVer component exceeds u64")
            return ProductSemVer(
                component(1),
                component(2),
                component(3),
                match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty(),
                match.groupValues[5].takeIf(String::isNotEmpty)?.split('.').orEmpty(),
                value,
            )
        }
    }
}

internal data class ChannelDocument(
    val channel: String,
    val releaseIdentity: ProductSemVer,
    val manifestUrl: URI,
    val manifestSize: Long,
    val manifestSha256: String,
) {
    fun canonicalBytes(): ByteArray =
        (
            "{\"schemaVersion\":1,\"channel\":${jsonString(channel)}," +
                "\"releaseIdentity\":${jsonString(releaseIdentity.source)}," +
                "\"manifestUrl\":${jsonString(manifestUrl.toASCIIString())}," +
                "\"manifestSize\":$manifestSize,\"manifestSha256\":${jsonString(manifestSha256)}}\n"
        ).toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): ChannelDocument {
            val json = strictObject(bytes)
            requireKeys(
                json,
                setOf("schemaVersion", "channel", "releaseIdentity", "manifestUrl", "manifestSize", "manifestSha256"),
                "channel",
            )
            requireContract(integer(json, "schemaVersion") == 1L, "Unsupported channel schema")
            val channel = json.getString("channel")
            val releaseIdentity = ProductSemVer.parse(json.getString("releaseIdentity"))
            when (channel) {
                "stable" -> requireContract(releaseIdentity.prerelease.isEmpty(), "Stable channel points to prerelease")
                "beta" -> requireContract(releaseIdentity.prerelease.firstOrNull()?.startsWith("beta") == true, "Beta channel mismatch")
                else -> throw ProductContractException("Unsupported channel")
            }
            val url = parseHttpsUrl(json.getString("manifestUrl"))
            val size = integer(json, "manifestSize")
            requireContract(size in 1..MAX_MANIFEST_BYTES, "Manifest size is outside bound")
            val sha256 = json.getString("manifestSha256")
            requireSha256(sha256, "manifestSha256")
            return ChannelDocument(channel, releaseIdentity, url, size, sha256).also {
                requireContract(it.canonicalBytes().contentEquals(bytes), "Channel JSON is not canonical")
            }
        }

        const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
    }
}

internal data class ProductCompatibility(
    val installerProtocol: Long,
    val operationProtocol: Long,
    val appDaemonProtocol: Long,
    val agentCatalogSchema: Long,
    val agentEntryAbi: Long,
    val implantAbi: Long,
) {
    fun canonical(): String =
        "{\"installerProtocol\":$installerProtocol,\"operationProtocol\":$operationProtocol," +
            "\"appDaemonProtocol\":$appDaemonProtocol,\"agentCatalogSchema\":$agentCatalogSchema," +
            "\"agentEntryAbi\":$agentEntryAbi,\"implantAbi\":$implantAbi}"
}

internal data class ProductArtifact(
    val role: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val activation: String,
    val packageName: String?,
    val versionCode: Long?,
    val certificateSha256: String?,
    val abi: String?,
) {
    fun canonical(): String =
        buildString {
            append("{\"role\":${jsonString(role)},\"file\":${jsonString(file)},\"size\":$size,")
            append("\"sha256\":${jsonString(sha256)},\"activation\":${jsonString(activation)}")
            packageName?.let { append(",\"packageName\":${jsonString(it)}") }
            versionCode?.let { append(",\"versionCode\":$it") }
            certificateSha256?.let { append(",\"certificateSha256\":${jsonString(it)}") }
            abi?.let { append(",\"abi\":${jsonString(it)}") }
            append('}')
        }
}

internal data class ProductManifest(
    val releaseIdentity: ProductSemVer,
    val compatibility: ProductCompatibility,
    val artifacts: List<ProductArtifact>,
    val exactBytes: ByteArray,
) {
    val sha256ByFile: Map<String, String> = artifacts.associate { it.file to it.sha256 }

    companion object {
        fun parse(bytes: ByteArray): ProductManifest {
            val json = strictObject(bytes)
            requireKeys(json, setOf("schemaVersion", "releaseIdentity", "compatibility", "artifacts"), "manifest")
            requireContract(integer(json, "schemaVersion") == 1L, "Unsupported manifest schema")
            val release = ProductSemVer.parse(json.getString("releaseIdentity"))
            val compatibilityJson = json.getJSONObject("compatibility")
            val compatibilityKeys =
                setOf("installerProtocol", "operationProtocol", "appDaemonProtocol", "agentCatalogSchema", "agentEntryAbi", "implantAbi")
            requireKeys(compatibilityJson, compatibilityKeys, "compatibility")
            val compatibility =
                ProductCompatibility(
                    integer(compatibilityJson, "installerProtocol"),
                    integer(compatibilityJson, "operationProtocol"),
                    integer(compatibilityJson, "appDaemonProtocol"),
                    integer(compatibilityJson, "agentCatalogSchema"),
                    integer(compatibilityJson, "agentEntryAbi"),
                    integer(compatibilityJson, "implantAbi"),
                )
            requireContract(
                listOf(
                    compatibility.installerProtocol,
                    compatibility.operationProtocol,
                    compatibility.appDaemonProtocol,
                    compatibility.agentCatalogSchema,
                    compatibility.agentEntryAbi,
                    compatibility.implantAbi,
                ).all { it in 1..0xffff_ffffL },
                "Compatibility value is outside bound",
            )
            requireContract(compatibility.agentCatalogSchema == 2L, "Unsupported agent catalog schema")
            val array = json.getJSONArray("artifacts")
            requireContract(array.length() in 23..1024, "Artifact count is outside bound")
            val artifacts = (0 until array.length()).map { parseArtifact(array.getJSONObject(it)) }
            validateInventory(artifacts)
            val canonical =
                "{\"schemaVersion\":1,\"releaseIdentity\":${jsonString(release.source)}," +
                    "\"compatibility\":${compatibility.canonical()}," +
                    "\"artifacts\":[${artifacts.joinToString(",", transform = ProductArtifact::canonical)}]}\n"
            requireContract(canonical.toByteArray(Charsets.UTF_8).contentEquals(bytes), "Manifest JSON is not canonical")
            return ProductManifest(release, compatibility, artifacts, bytes.copyOf())
        }
    }
}

private data class ArtifactContract(
    val role: String,
    val activation: String,
    val abi: String? = null,
    val rank: Int,
    val maxSize: Long = 64L * 1024L * 1024L,
)

private fun parseArtifact(json: JSONObject): ProductArtifact {
    val baseKeys = setOf("role", "file", "size", "sha256", "activation")
    val allowed = baseKeys + setOf("packageName", "versionCode", "certificateSha256", "abi")
    requireContract(json.keys().asSequence().toSet().all { it in allowed }, "Unknown artifact field")
    requireContract(baseKeys.all(json::has), "Missing artifact field")
    val file = json.getString("file")
    requireContract(file.isNotEmpty() && file.length <= 255 && file !in setOf(".", ".."), "Invalid artifact basename")
    requireContract(file.none { it == '/' || it == '\\' || it == '\u0000' }, "Invalid artifact basename")
    val contract = classifyArtifact(file)
    val role = json.getString("role")
    val activation = json.getString("activation")
    val abi = json.optString("abi").takeIf { json.has("abi") }
    requireContract(Triple(role, activation, abi) == Triple(contract.role, contract.activation, contract.abi), "Artifact metadata mismatch")
    val size = integer(json, "size")
    requireContract(size in 1..contract.maxSize, "Artifact size is outside bound")
    val sha256 = json.getString("sha256").also { requireSha256(it, "artifact sha256") }
    val packageName = json.optString("packageName").takeIf { json.has("packageName") }
    val versionCode = if (json.has("versionCode")) integer(json, "versionCode") else null
    val certificate = json.optString("certificateSha256").takeIf { json.has("certificateSha256") }
    if (role == "app") {
        requireContract(
            packageName == "ru.voboost" && versionCode != null && versionCode in 1..2_100_000_000L && certificate != null,
            "Incomplete APK identity",
        )
        requireSha256(requireNotNull(certificate), "certificateSha256")
    } else {
        requireContract(packageName == null && versionCode == null && certificate == null, "APK metadata on non-app artifact")
    }
    return ProductArtifact(role, file, size, sha256, activation, packageName, versionCode, certificate, abi)
}

private fun validateInventory(artifacts: List<ProductArtifact>) {
    val files = mutableSetOf<String>()
    val roles = mutableMapOf<String, Int>()
    var previous: Pair<Int, String>? = null
    artifacts.forEach { artifact ->
        requireContract(files.add(artifact.file), "Duplicate artifact filename")
        roles[artifact.role] = roles.getOrDefault(artifact.role, 0) + 1
        val key = classifyArtifact(artifact.file).rank to artifact.file
        previous?.let {
            requireContract(it.first < key.first || it.first == key.first && it.second < key.second, "Artifact order is not canonical")
        }
        previous = key
    }
    REQUIRED_FILES.forEach { requireContract(it in files, "Required artifact is missing: $it") }
    requireContract(artifacts.any { it.role == "agent-module" }, "Agent module is missing")
    roles.filterKeys { it !in setOf("implant", "lsplant", "agent-module", "qnx-agent-module") }.values.forEach {
        requireContract(it == 1, "Duplicate singleton role")
    }
}

private fun classifyArtifact(file: String): ArtifactContract {
    return when (file) {
        "voboost-app.apk" -> ArtifactContract("app", "package-installer", rank = 0, maxSize = 128L * 1024L * 1024L)
        "voboost-daemon" -> ArtifactContract("daemon", "native-runtime", rank = 1)
        "voboost-inject" -> ArtifactContract("inject", "native-runtime", rank = 2)
        "voboost-install" -> ArtifactContract("install", "native-runtime", rank = 3)
        "voboost-restore" -> ArtifactContract("restore", "restore-authority", rank = 4)
        "voboost-uninstall" -> ArtifactContract("uninstall", "restore-authority", rank = 5)
        "voboost-selinux" -> ArtifactContract("selinux", "native-runtime", rank = 6)
        "voboost.rc" -> ArtifactContract("init", "init", rank = 7)
        "restore.json" -> ArtifactContract("restore-authorization", "restore-authority", rank = 8, maxSize = 4L * 1024L * 1024L)
        "restore.json.sig" -> ArtifactContract("restore-signature", "restore-authority", rank = 9, maxSize = 64)
        "libimplant-arm64-v8a.so" -> ArtifactContract("implant", "native-runtime", "arm64-v8a", 10)
        "libimplant-armeabi-v7a.so" -> ArtifactContract("implant", "native-runtime", "armeabi-v7a", 10)
        "liblsplant-arm64-v8a.so" -> ArtifactContract("lsplant", "native-runtime", "arm64-v8a", 11)
        "liblsplant-armeabi-v7a.so" -> ArtifactContract("lsplant", "native-runtime", "armeabi-v7a", 11)
        "agents.json" -> ArtifactContract("agent-catalog", "agent-generation", rank = 12, maxSize = 4L * 1024L * 1024L)
        "agents.json.sig" -> ArtifactContract("agent-catalog-signature", "agent-generation", rank = 13, maxSize = 64)
        "agent-common.dex" -> ArtifactContract("agent-common", "agent-generation", rank = 14)
        "voboost-cluster-daemon" -> ArtifactContract("qnx-daemon", "native-runtime", "qnx-aarch64", rank = 16)
        "voboost-cluster-startup.sh" -> ArtifactContract("qnx-init", "init", rank = 17)
        "libvoboost-cluster-inject.so" -> ArtifactContract("qnx-implant", "native-runtime", "qnx-aarch64", rank = 18)
        "libvoboost-runtime-cluster.so" -> ArtifactContract("qnx-runtime", "agent-generation", "qnx-aarch64", rank = 19)

        else -> {
            if (file.matches(Regex("^libagent-[a-z0-9]+(?:-[a-z0-9]+)*\\.so$"))) {
                return ArtifactContract("qnx-agent-module", "agent-generation", "qnx-aarch64", rank = 20)
            }
            requireContract(file.matches(Regex("^agent-[a-z0-9]+(?:-[a-z0-9]+)*\\.dex$")) && file != "agent-common.dex", "Unknown artifact")
            ArtifactContract("agent-module", "agent-generation", rank = 15)
        }
    }
}

private fun strictObject(bytes: ByteArray): JSONObject {
    requireContract(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte(), "JSON requires final LF")
    requireContract(bytes.size < 2 || bytes[bytes.lastIndex - 1] != '\n'.code.toByte(), "JSON has multiple final LF")
    val text = bytes.toString(Charsets.UTF_8)
    try {
        DuplicateKeyJsonValidator.validate(text)
        return JSONObject(text)
    } catch (error: ProductContractException) {
        throw error
    } catch (error: Exception) {
        throw ProductContractException("Invalid JSON", error)
    }
}

private fun integer(
    json: JSONObject,
    name: String,
): Long {
    val value = json.get(name)
    requireContract(value is Int || value is Long, "$name must be an integer")
    return (value as Number).toLong()
}

private fun requireKeys(
    json: JSONObject,
    expected: Set<String>,
    name: String,
) {
    requireContract(json.keys().asSequence().toSet() == expected, "$name fields are not closed")
}

private fun requireSha256(
    value: String,
    name: String,
) {
    requireContract(value.matches(Regex("^[0-9a-f]{64}$")), "$name is invalid")
}

internal fun parseHttpsUrl(value: String): URI {
    requireContract(value.length <= 2048, "URL is too long")
    val uri =
        try {
            URI(value)
        } catch (error: Exception) {
            throw ProductContractException("Invalid URL", error)
        }
    requireContract(uri.scheme == "https" && !uri.host.isNullOrEmpty(), "URL must use HTTPS")
    requireContract(uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null, "URL authority is invalid")
    requireContract(uri.host.split('.').none { it.startsWith("xn--", ignoreCase = true) }, "IDN release origins are forbidden")
    requireContract(uri.rawPath.startsWith('/'), "URL path must be absolute")
    return uri
}

internal fun jsonString(value: String): String =
    buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

private fun requireContract(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw ProductContractException(message)
}

private val REQUIRED_FILES =
    setOf(
        "voboost-app.apk",
        "voboost-daemon",
        "voboost-inject",
        "voboost-install",
        "voboost-restore",
        "voboost-uninstall",
        "voboost-selinux",
        "voboost.rc",
        "restore.json",
        "restore.json.sig",
        "libimplant-arm64-v8a.so",
        "libimplant-armeabi-v7a.so",
        "agents.json",
        "agents.json.sig",
        "agent-common.dex",
        "voboost-cluster-daemon",
        "voboost-cluster-startup.sh",
        "libvoboost-cluster-inject.so",
        "libvoboost-runtime-cluster.so",
        "libagent-font-cyrillic.so",
    )
