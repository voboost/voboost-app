import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("checkstyle")
    id("com.diffplug.spotless") version "6.25.0"
}

apply(from = "../voboost-codestyle/codestyle.gradle")
apply(from = "scripts/readable-licenses.gradle.kts")

dependencyLocking {
    lockAllConfigurations()
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val version = requested.version ?: return@eachDependency
        require(
            '+' !in version && '[' !in version && ']' !in version &&
                '(' !in version && ')' !in version &&
                !version.startsWith("latest.") && !version.endsWith("-SNAPSHOT"),
        ) { "Dynamic dependency versions are forbidden: ${requested.group}:${requested.name}:$version" }
    }
}

val appVersionCode = 1_000_019
require(appVersionCode in 1..2_100_000_000) {
    "Android versionCode must satisfy the product contract 1..2100000000"
}
val disabledChannelUrl = "TODO_SET_CHANNEL_URL"
val courierLicenseAssets by tasks.registering(Sync::class) {
    from("src/main/license-assets") {
        include("licenses/LICENSE", "licenses/LICENSE.ru.md", "licenses/android-jvm-NOTICES.txt")
    }
    into(layout.buildDirectory.dir("generated/courier-license-assets"))
}
val localProperties = Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
val configuredChannelUrl =
    System.getenv("VOBOOST_CHANNEL_URL")
        ?.takeIf(String::isNotBlank)
        ?: localProperties.getProperty("ota.channelUrl")
        ?: disabledChannelUrl
val debuggableBuild = providers.gradleProperty("debuggable").orNull == "true"
val unsignedRelease = providers.gradleProperty("voboostUnsignedRelease").orNull == "true"
val protectedPackaging =
    System.getenv("VOBOOST_PROTECTED_RELEASE") == "1" ||
        System.getenv("VOBOOST_CI_RELEASE") == "1"
val embeddedProductPublicKeyPem =
    System.getenv("VOBOOST_EMBEDDED_PRODUCT_PUBLIC_KEY_PEM")
        ?.takeIf(String::isNotBlank)
        ?.let(::file)
require(!unsignedRelease || !debuggableBuild) {
    "Unsigned preparation requires a non-debuggable build"
}
require(
    !unsignedRelease ||
        listOf(
            System.getenv("KEYSTORE_PASSWORD"),
            System.getenv("VOBOOST_RELEASE_KEYSTORE_FILE"),
            providers.gradleProperty("voboostKeystorePassword").orNull,
            providers.gradleProperty("voboostReleaseKeystoreFile").orNull,
        ).all { it.isNullOrEmpty() },
) { "Unsigned preparation must not receive signing credentials" }
require(!unsignedRelease || embeddedProductPublicKeyPem?.isFile == true) {
    "Unsigned preparation requires VOBOOST_EMBEDDED_PRODUCT_PUBLIC_KEY_PEM"
}

fun ed25519RawRoot(pemBytes: ByteArray): ByteArray {
    val text = pemBytes.toString(Charsets.US_ASCII).trim()
    val match =
        requireNotNull(
            Regex(
                "^-----BEGIN PUBLIC KEY-----\\s+([A-Za-z0-9+/=\\r\\n]+)\\s+-----END PUBLIC KEY-----$",
            ).matchEntire(text),
        ) { "Product public key must be one PEM PUBLIC KEY block" }
    val der = Base64.getMimeDecoder().decode(match.groupValues[1])
    val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    require(der.size == prefix.size + 32 && der.copyOfRange(0, prefix.size).contentEquals(prefix)) {
        "Product public key must be an exact Ed25519 SPKI"
    }
    KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
    return der.copyOfRange(prefix.size, der.size)
}

val generatedProductRoot = layout.buildDirectory.file("generated/product-root/config/release-public.pem")
val prepareEmbeddedProductRoot =
    embeddedProductPublicKeyPem?.let { source ->
        tasks.register("prepareEmbeddedProductRoot") {
            inputs.file(source)
            outputs.file(generatedProductRoot)
            doLast {
                val bytes = source.readBytes()
                ed25519RawRoot(bytes)
                val target = generatedProductRoot.get().asFile.toPath()
                Files.createDirectories(target.parent)
                val temporary = Files.createTempFile(target.parent, ".release-public.", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }
    }

android {
    namespace = "ru.voboost"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "29.0.14206865"
    testBuildType = "release"

    defaultConfig {
        applicationId = "ru.voboost"
        minSdk = 28
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionCode.toString()
        buildConfigField("String", "LOG_LEVEL", "\"info\"")
        buildConfigField("String", "CHANNEL_URL", "\"$configuredChannelUrl\"")
        buildConfigField("boolean", "OTA_DISABLED", "${configuredChannelUrl == disabledChannelUrl}")
        testInstrumentationRunner = "ru.voboost.ota.EmulatorInteropInstrumentation"
    }

    signingConfigs {
        create("release") {
            val password =
                System.getenv("KEYSTORE_PASSWORD")
                    ?: providers.gradleProperty("voboostKeystorePassword").orNull
                    ?: ""
            storeFile =
                System.getenv("VOBOOST_RELEASE_KEYSTORE_FILE")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::file)
                    ?: providers.gradleProperty("voboostReleaseKeystoreFile").orNull?.let(::file)
                    ?: layout.buildDirectory.file("missing-release-keystore").get().asFile
            storePassword = password
            keyAlias = "voboost"
            keyPassword = password
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = debuggableBuild
            buildConfigField("String", "LOG_LEVEL", if (isDebuggable) "\"debug\"" else "\"info\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("release")
        }
    }

    lint {
        disable.addAll(
            setOf(
                "AndroidGradlePluginVersion",
                "ChromeOsAbiSupport",
                "ExpiredTargetSdkVersion",
                "GradleDependency",
                "ProtectedPermissions",
            ),
        )
        warningsAsErrors = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    buildFeatures.buildConfig = true
    // When a pinned external product root replaces the asset tree, every other
    // file under src/main/assets would silently vanish from the APK. Hold the
    // asset inventory to exactly the release root so any addition fails loudly.
    val pinnedAssetFiles =
        fileTree("src/main/assets") {
            include("**/*")
        }.files.filter(File::isFile)
    val allowedAssetPath = "config/release-public.pem"
    require(
        pinnedAssetFiles.all {
            it.relativeTo(file("src/main/assets")).path == allowedAssetPath
        },
    ) {
        "src/main/assets may only contain $allowedAssetPath; other files " +
            "would disappear from pinned-PEM builds: ${pinnedAssetFiles.map { it.name }}"
    }
    sourceSets.getByName("main").assets.setSrcDirs(
        listOf(
            if (embeddedProductPublicKeyPem != null) {
                generatedProductRoot.get().asFile.parentFile.parentFile
            } else {
                file("src/main/assets")
            },
            courierLicenseAssets,
        ),
    )
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.resources.excludes += "org/bouncycastle/pqc/**"
    packaging.resources.excludes += "org/bouncycastle/x509/**"
    packaging.resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
    packaging.resources.excludes += "DebugProbesKt.bin"
    packaging.resources.excludes += "kotlin-tooling-metadata.json"
}

prepareEmbeddedProductRoot?.let { task -> tasks.named("preBuild") { dependsOn(task) } }
tasks.named("preBuild") { dependsOn(courierLicenseAssets) }

androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType != "debug" }
}

val copyReleaseApk by tasks.registering {
    dependsOn("packageRelease")
    val source =
        layout.buildDirectory.file(
            "outputs/apk/release/" +
                if (unsignedRelease) "voboost-app-release-unsigned.apk" else "voboost-app-release.apk",
        )
    val target = layout.buildDirectory.file("outputs/apk/release/voboost-app.apk")
    inputs.file(source)
    outputs.file(target)
    doLast { source.get().asFile.copyTo(target.get().asFile, overwrite = true) }
}

/** Produces non-authoritative product-compositor input from the exact signed APK. */
abstract class ProduceApkEvidenceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aapt2File: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkSignerFile: RegularFileProperty

    @get:Input
    abstract val sourceCommit: Property<String>

    @get:OutputFile
    abstract val evidenceFile: RegularFileProperty

    @TaskAction
    fun produce() {
        val commit = sourceCommit.get()
        require(commit.matches(Regex("^[0-9a-f]{40}$"))) {
            "voboostSourceCommit must be a full lowercase Git commit"
        }
        val apk = apkFile.get().asFile
        require(apk.isFile && apk.name == "voboost-app.apk") {
            "APK evidence requires the exact final voboost-app.apk"
        }

        val badging = runTool(aapt2File.get().asFile, "dump", "badging", apk.path)
        val packageLine =
            badging.lineSequence().singleOrNull { it.startsWith("package: ") }
                ?: error("aapt2 must report exactly one package line")
        val packageMatch =
            Regex("^package: name='([^']+)' versionCode='([0-9]+)'").find(packageLine)
                ?: error("aapt2 package line is malformed")
        val packageName = packageMatch.groupValues[1]
        val versionCode = packageMatch.groupValues[2].toLongOrNull()
        require(packageName == "ru.voboost") { "APK package must be ru.voboost" }
        require(versionCode != null && versionCode > 0) { "APK versionCode must be positive" }

        val signer =
            runTool(
                apkSignerFile.get().asFile,
                "verify",
                "--verbose",
                "--print-certs",
                apk.path,
            )
        require(
            signer.lineSequence().any {
                it == "Verified using v2 scheme (APK Signature Scheme v2): true" ||
                    it == "Verified using v3 scheme (APK Signature Scheme v3): true" ||
                    it == "Verified using v3.1 scheme (APK Signature Scheme v3.1): true"
            },
        ) { "APK evidence requires a verified v2/v3 signer" }
        require(signer.lineSequence().singleOrNull { it.startsWith("Number of signers: ") } == "Number of signers: 1") {
            "APK evidence requires exactly one signer"
        }
        val certificateSha256 =
            signer.lineSequence()
                .singleOrNull { it.startsWith("Signer #1 certificate SHA-256 digest: ") }
                ?.substringAfterLast(' ')
                ?: error("apksigner did not report the signer certificate SHA-256")
        require(certificateSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "Signer certificate SHA-256 must be lowercase hexadecimal"
        }

        val sha256 =
            apk.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        val bytes =
            (
                "{\"schemaVersion\":1,\"sourceCommit\":\"$commit\"," +
                    "\"file\":\"voboost-app.apk\",\"size\":${apk.length()}," +
                    "\"sha256\":\"$sha256\",\"packageName\":\"ru.voboost\"," +
                    "\"versionCode\":$versionCode," +
                    "\"certificateSha256\":\"$certificateSha256\"}\n"
            ).toByteArray(Charsets.UTF_8)
        val target = evidenceFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".voboost-app-evidence.", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun runTool(
        executable: File,
        vararg arguments: String,
    ): String {
        require(executable.isFile) { "Missing Android build tool: ${executable.path}" }
        val output = ByteArrayOutputStream()
        val process =
            ProcessBuilder(listOf(executable.path) + arguments)
                .redirectErrorStream(true)
                .start()
        process.inputStream.use { it.copyTo(output) }
        val exitCode = process.waitFor()
        val text = output.toString(Charsets.UTF_8.name())
        require(exitCode == 0) { "${executable.name} failed ($exitCode): $text" }
        return text
    }
}

val androidBuildTools = android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}")

tasks.register<ProduceApkEvidenceTask>("apkEvidence") {
    group = "build"
    description = "Inspect the final signed APK and write non-authoritative canonical evidence"
    dependsOn(copyReleaseApk)
    apkFile.set(layout.buildDirectory.file("outputs/apk/release/voboost-app.apk"))
    aapt2File.set(androidBuildTools.resolve("aapt2"))
    apkSignerFile.set(androidBuildTools.resolve("apksigner"))
    sourceCommit.set(providers.gradleProperty("voboostSourceCommit"))
    evidenceFile.set(layout.buildDirectory.file("outputs/apk/release/voboost-app-evidence.json"))
}

tasks.configureEach {
    if (name == "packageRelease") {
        doFirst {
            if (protectedPackaging) {
                require(configuredChannelUrl != disabledChannelUrl) {
                    "Protected packaging requires VOBOOST_CHANNEL_URL"
                }
                val uri = URI(configuredChannelUrl)
                require(
                    uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                        uri.rawUserInfo == null && uri.rawPath.endsWith("/channel.json") &&
                        uri.rawQuery == null && uri.rawFragment == null,
                ) { "VOBOOST_CHANNEL_URL must be an absolute HTTPS channel.json URL" }
            }
        }
    }
}

abstract class FilteredUnitTestAlias : DefaultTask() {
    @Option(option = "tests", description = "Test class or method pattern")
    fun includeTestsMatching(pattern: String) {
        project.tasks.named<Test>("testReleaseUnitTest").configure {
            filter.includeTestsMatching(pattern)
        }
    }
}

tasks.register<FilteredUnitTestAlias>("testUnit") {
    group = "verification"
    dependsOn("testReleaseUnitTest")
}

tasks.register("verifyApkInventory") {
    group = "verification"
    dependsOn(copyReleaseApk)
    val apk = layout.buildDirectory.file("outputs/apk/release/voboost-app.apk")
    val dependencyReport = layout.buildDirectory.file("reports/apk/dependency-resources.txt")
    inputs.file(apk)
    outputs.file(dependencyReport)
    doLast {
        val file = apk.get().asFile
        require(file.isFile) { "Missing ${file.path}" }
        ZipFile(file).use { zip ->
            val forbidden =
                zip.entries().asSequence().map { it.name }.filter {
                    it.startsWith("assets/agents/") ||
                        (it.startsWith("assets/") && it.endsWith(".dex")) ||
                        it.endsWith("agents.json") ||
                        it.endsWith("agents.json.sig") ||
                        it.startsWith("org/bouncycastle/pqc/")
                }.toList()
            require(forbidden.isEmpty()) { "APK contains forbidden agent assets: $forbidden" }
            val attributed =
                zip.entries().asSequence().filter {
                    it.name.startsWith("okhttp3/") ||
                        it.name.startsWith("okio/") ||
                        it.name.startsWith("org/bouncycastle/") ||
                        it.name.startsWith("kotlinx/coroutines/") ||
                        it.name.startsWith("META-INF/services/") ||
                        it.name.endsWith("DebugProbesKt.bin") ||
                        it.name.contains("kotlinx-coroutines")
                }.map { "${it.name}\t${it.size}" }.sorted().toList()
            val attributedNames = attributed.map { it.substringBefore('\t') }.toSet()
            require(
                attributedNames.containsAll(
                    setOf(
                        "okhttp3/internal/publicsuffix/NOTICE",
                        "okhttp3/internal/publicsuffix/publicsuffixes.gz",
                    ),
                ) &&
                    attributedNames.all {
                        it.startsWith("okhttp3/") ||
                            it.startsWith("okio/") ||
                            it.startsWith("org/bouncycastle/") ||
                            it.startsWith("kotlinx/coroutines/") ||
                            it.startsWith("META-INF/services/") ||
                            it.startsWith("META-INF/kotlinx_coroutines_")
                    },
            ) { "Dependency resource attribution changed: $attributedNames" }
            dependencyReport.get().asFile.apply {
                parentFile.mkdirs()
                writeText(attributed.joinToString("\n", postfix = if (attributed.isEmpty()) "" else "\n"))
            }
        }
    }
}

tasks.register("verifyPackagedProductRoot") {
    group = "verification"
    dependsOn(copyReleaseApk)
    val apk = layout.buildDirectory.file("outputs/apk/release/voboost-app.apk")
    val expected = embeddedProductPublicKeyPem ?: file("src/main/assets/config/release-public.pem")
    inputs.file(apk)
    inputs.file(expected)
    doLast {
        val expectedRaw = ed25519RawRoot(expected.readBytes())
        val packaged =
            ZipFile(apk.get().asFile).use { zip ->
                val entry = requireNotNull(zip.getEntry("assets/config/release-public.pem"))
                zip.getInputStream(entry).use { it.readBytes() }
            }
        require(ed25519RawRoot(packaged).contentEquals(expectedRaw)) {
            "Packaged product release-catalog root differs from explicit input"
        }
    }
}

tasks.register("verifySourceOwnership") {
    group = "verification"
    doLast {
        val production = fileTree("src/main") { include("**/*") }.files.filter(File::isFile)
        val forbiddenNames = setOf("AgentsExtractor.kt", "AgentStagingCoordinator.kt", "InstalledAppsProvider.java")
        require(production.none { it.name in forbiddenNames }) { "Agent-owned source remains in app" }
        val productionText = production.joinToString("\n") { it.readText() }
        val forbiddenLegacyAuthority =
            setOf(
                "Channel.INJECT",
                "changedNativeRoles",
                "stageInjectBundle",
                "voboost-inject.json",
                "current-voboost-inject.json",
                "ReleaseManifest",
                "ArtifactRole",
                "componentToChannel",
                "injectEntry",
            )
        val foundLegacyAuthority = forbiddenLegacyAuthority.filter(productionText::contains)
        require(foundLegacyAuthority.isEmpty()) {
            "Legacy component/native OTA authority remains: $foundLegacyAuthority"
        }
        require(!file("src/main/assets/agents").exists()) { "Embedded agent assets are forbidden" }
        val properties = file("gradle.properties").readText()
        require("voboostVersion" !in properties && "voboostBeta" !in properties) {
            "Product SemVer must not be an APK build input"
        }
        require(!file("package.json").exists() && !file("package-lock.json").exists()) {
            "App checks must remain Gradle/JVM-owned"
        }
    }
}

tasks.named("check") { dependsOn("verifySourceOwnership", "testLicenseContract") }
tasks.register("buildRelease") {
    group = "build"
    dependsOn("verifyApkInventory", "verifyPackagedProductRoot", "verifyPackagedLicenses")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    compileOnly("org.json:json:20231013")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
