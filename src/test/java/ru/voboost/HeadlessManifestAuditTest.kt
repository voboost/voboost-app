package ru.voboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.voboost.ota.ApkInstallerContract
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Source-level audit of the production manifest (docs/developer/application): the APK is a
 * headless network/package plane — no launcher entry, activity, application
 * theme/icon, or cleartext exception. Uninstall initiation moved to the
 * machine Settings host (daemon socket with peer attestation); the app never
 * initiates a removal.
 */
class HeadlessManifestAuditTest {
    private val manifest: String =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun noLauncherIntentFilterOrMainActionExists() {
        assertFalse(manifest.contains("android.intent.category.LAUNCHER"))
        assertFalse(manifest.contains("android.intent.action.MAIN"))
    }

    @Test
    fun noActivityEntryPointRemains() {
        val activities = Regex("<activity[^>]*/?>").findAll(manifest).toList()
        assertTrue("expected zero activities, saw ${activities.size}", activities.isEmpty())
    }

    @Test
    fun noApplicationIconOrThemeAttributeDeclared() {
        val applicationTag =
            Regex("<application[\\s\\S]*?>").find(manifest)?.value ?: ""
        assertFalse(applicationTag.contains("android:icon"))
        assertFalse(applicationTag.contains("android:theme"))
    }

    @Test
    fun noCleartextTrafficExceptionDeclared() {
        assertFalse(manifest.contains("usesCleartextTraffic"))
    }

    @Test
    fun apkProviderIsPrivateDescriptorBoundAndExposesNoFilesystemPaths() {
        val provider =
            Regex(
                "<provider(?=[^>]*android:name=\".ota.DescriptorBoundApkProvider\")" +
                    "[^>]*/>",
            ).find(manifest)?.value.orEmpty()

        assertTrue(provider.contains("android:authorities=\"ru.voboost.apk-share\""))
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertFalse(manifest.contains("androidx.core.content.FileProvider"))
        assertFalse(manifest.contains("android.support.FILE_PROVIDER_PATHS"))
        assertFalse(File("src/main/res/xml/file_paths.xml").exists())
    }

    @Test
    fun apkInstallerContractMatchesSupportedStructuralSurface() {
        val fixtureStream =
            checkNotNull(javaClass.getResourceAsStream(OEM_INSTALLER_FIXTURE)) {
                "Missing PackageInstaller contract fixture"
            }
        val fixture = fixtureStream.use(::readInstallerContract)

        assertEquals(ApkInstallerContract.TRUSTED_PACKAGE, fixture.packageName)
        assertEquals(ApkInstallerContract.TRUSTED_ACTIVITY, fixture.activityName)
        assertTrue(fixture.exported)
        assertTrue(fixture.actions.contains("android.intent.action.INSTALL_PACKAGE"))
        assertTrue(fixture.schemes.contains("content"))
        assertTrue(fixture.mimeTypes.contains("application/vnd.android.package-archive"))
    }

    @Test
    fun bootReceiverIsPrivateButRetainsTheSystemBootAction() {
        val bootReceiver =
            Regex(
                "<receiver(?=[^>]*android:name=\".BootReceiver\")" +
                    "[^>]*>[\\s\\S]*?</receiver>",
            ).find(manifest)?.value.orEmpty()
        assertTrue(bootReceiver.contains("android:enabled=\"true\""))
        assertTrue(bootReceiver.contains("android:exported=\"false\""))
        assertTrue(bootReceiver.contains("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun exportedEntryPointsUseExactPlatformOrSignaturePermissions() {
        val otaJobService =
            Regex(
                "<service(?=[^>]*android:name=\".OtaJobService\")[^>]*/>",
            )
                .find(manifest)?.value.orEmpty()
        assertTrue(otaJobService.contains("android:exported=\"true\""))
        assertTrue(otaJobService.contains("android.permission.BIND_JOB_SERVICE"))
        assertFalse(manifest.contains("Uninstall" + "RequestService"))
        assertFalse(manifest.contains("ru.voboost.action.REQUEST_" + "UNINSTALL"))
        assertFalse(manifest.contains("ru.voboost.action.CONFIRM_" + "UNINSTALL"))
        assertFalse(manifest.contains(".UninstallActivity"))
        assertFalse(manifest.contains(".InstallActivity"))
        assertFalse(manifest.contains(".AgentExtractionService"))
        assertFalse(manifest.contains("androidx.startup.InitializationProvider"))
        assertFalse(manifest.contains("androidx.profileinstaller.ProfileInstallReceiver"))
    }

    @Test
    fun appNeverInitiatesAnUninstallRemoval() {
        // The Settings-host confirm dialog and the daemon socket own the
        // removal; no app source publishes an uninstall request marker.
        val main = File("src/main/java")
        if (main.isDirectory) {
            val names = main.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            assertFalse(names.any { it.contains("Uninstall") })
        }
        val paths = File("src/main/java/ru/voboost/Paths.kt").readText()
        assertFalse(paths.contains("uninstall"))
    }

    @Test
    fun componentPermissionBypassAdmitsSupportedSystemUidCallerBeforePermissionLookup() {
        val fixtureStream =
            checkNotNull(javaClass.getResourceAsStream(OEM_PERMISSION_FIXTURE)) {
                "Missing Settings system-UID contract fixture"
            }
        val fixture = fixtureStream.use(::readPermissionContract)

        assertEquals("com.qinggan.app.setting", fixture.packageName)
        assertEquals("android.uid.system", fixture.sharedUserId)
        val androidNineContract =
            checkNotNull(javaClass.getResource(AOSP_COMPONENT_PERMISSION_FIXTURE)).readText()
        val rootAndSystemBypass =
            androidNineContract.indexOf(
                "appId == Process.ROOT_UID || appId == Process.SYSTEM_UID",
            )
        val ownerBypass = androidNineContract.indexOf("UserHandle.isSameApp(uid, owningUid)")
        val permissionLookup = androidNineContract.indexOf("checkUidPermission(permission, uid)")
        assertTrue(rootAndSystemBypass >= 0)
        assertTrue(ownerBypass > rootAndSystemBypass)
        assertTrue(permissionLookup > ownerBypass)
    }

    @Test
    fun processApplicationOwnsTheSharedLogger() {
        assertTrue(manifest.contains("android:name=\".App\""))
        val jobSources = File("src/main/java/ru/voboost/OtaJobService.kt").readText()
        assertFalse(jobSources.contains("Logger.init("))
        assertFalse(jobSources.contains("Logger.shutdown("))
    }

    @Test
    fun channelEndpointHasDevelopmentSentinelAndOneProductionDaemonTransport() {
        val gradle = File("build.gradle.kts").readText()
        val job = File("src/main/java/ru/voboost/OtaJobService.kt").readText()
        assertTrue(gradle.contains("\"TODO_SET_CHANNEL_URL\""))
        assertTrue(gradle.contains("VOBOOST_CHANNEL_URL"))
        assertFalse(gradle.contains("voboostDaemonGatewayEnabled"))
        assertTrue(job.contains("LocalSocketDaemonOtaGateway()"))
        assertFalse(job.contains("UnavailableDaemonOtaGateway"))
        assertTrue(gradle.contains("name == \"packageRelease\""))
        assertFalse(gradle.contains("raw.githubusercontent.com/voboost/voboost-install"))
    }

    @Test
    fun noAgentCarrierOrResidentForegroundServiceRemains() {
        val main = File("src/main").walkTopDown().filter { it.isFile }.toList()
        assertFalse(main.any { it.name == "AgentsExtractor.kt" })
        assertFalse(main.any { it.name == "AgentStagingCoordinator.kt" })
        assertFalse(main.any { it.name == "InstalledAppsProvider.java" })
        assertFalse(manifest.contains("FOREGROUND_SERVICE"))
        assertFalse(manifest.contains(".VoboostService"))
        assertFalse(File("src/main/assets/agents").exists())
    }

    @Test
    fun headlessAppHasOnlyTheInvariantBrandResource() {
        val defaults = File("src/main/res/values/strings.xml")
        assertTrue(defaults.isFile)
        val resources = File("src/main/res").walkTopDown().filter { it.isFile && it.name == "strings.xml" }.toList()
        resources.forEach { file ->
            assertEquals(file.path, setOf("app_name"), stringNames(file))
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            assertEquals("Voboost", document.getElementsByTagName("string").item(0).textContent)
        }
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
    }

    private fun stringNames(file: File): Set<String> = Regex("name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun noFeaturePlaneClassesRemainInMainSources() {
        val removed =
            listOf(
                "FeatureManager",
                "PlanProducer",
                "EventsReader",
                "StatusReader",
                "AgentPlanProjector",
                "AgentPlanContext",
                "MediaAgentGroupReconciler",
                "MediaStatusReapplyPolicy",
            )
        val mainDir = File("src/main/java")
        if (mainDir.isDirectory) {
            val files = mainDir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            for (name in removed) {
                assertFalse("feature-plane class remains: $name", files.any { it.contains(name) })
            }
        }
    }

    private fun readPermissionContract(input: InputStream): PermissionContract {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(input)
        val root = document.documentElement
        return PermissionContract(
            packageName = root.getAttribute("package"),
            sharedUserId = root.getAttributeNS(ANDROID_NAMESPACE, "sharedUserId"),
        )
    }

    private fun readInstallerContract(input: InputStream): InstallerContract {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(input)
        val root = document.documentElement
        val activities = document.getElementsByTagName("activity")
        val activity =
            (0 until activities.length)
                .map(activities::item)
                .single {
                    it.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue ==
                        ApkInstallerContract.TRUSTED_ACTIVITY
                }
        val descendants = activity.childNodes
        val actions = mutableSetOf<String>()
        val schemes = mutableSetOf<String>()
        val mimeTypes = mutableSetOf<String>()
        repeat(descendants.length) { filterIndex ->
            val filter = descendants.item(filterIndex)
            val children = filter.childNodes
            repeat(children.length) { childIndex ->
                val child = children.item(childIndex)
                when (child.nodeName) {
                    "action" ->
                        actions +=
                            child.attributes
                                .getNamedItemNS(ANDROID_NAMESPACE, "name")
                                .nodeValue
                    "data" -> {
                        child.attributes.getNamedItemNS(ANDROID_NAMESPACE, "scheme")
                            ?.nodeValue?.let(schemes::add)
                        child.attributes.getNamedItemNS(ANDROID_NAMESPACE, "mimeType")
                            ?.nodeValue?.let(mimeTypes::add)
                    }
                }
            }
        }
        return InstallerContract(
            packageName = root.getAttribute("package"),
            activityName =
                activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue,
            exported =
                activity.attributes
                    .getNamedItemNS(ANDROID_NAMESPACE, "exported")
                    .nodeValue == "true",
            actions = actions,
            schemes = schemes,
            mimeTypes = mimeTypes,
        )
    }

    private data class PermissionContract(
        val packageName: String,
        val sharedUserId: String,
    )

    private data class InstallerContract(
        val packageName: String,
        val activityName: String,
        val exported: Boolean,
        val actions: Set<String>,
        val schemes: Set<String>,
        val mimeTypes: Set<String>,
    )

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val OEM_PERMISSION_FIXTURE = "/oem/settings-system-uid-contract.xml"
        private const val OEM_INSTALLER_FIXTURE = "/oem/package-installer-contract.xml"
        private const val AOSP_COMPONENT_PERMISSION_FIXTURE =
            "/aosp/android-9-component-permission-contract.java.txt"
    }
}
