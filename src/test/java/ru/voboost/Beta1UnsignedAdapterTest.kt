package ru.voboost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Beta1UnsignedAdapterTest {
    @Test
    fun adapterIsSecretlessDeterministicMinifiedAndProductionTransportEnabled() {
        val script = File("scripts/prepare-beta1-unsigned.sh").readText()
        val build = File("build.gradle.kts").readText()

        assertTrue(script.contains("[[ ! -e \"\$output\" && ! -L \"\$output\" ]]"))
        assertTrue(script.contains("repository=\"\$(CDPATH= cd -- \"\$(dirname -- \"\$0\")/..\" && pwd -P)\""))
        assertTrue(script.contains("cd \"\$repository\""))
        assertTrue(script.contains("KEYSTORE_PASSWORD"))
        assertTrue(script.contains("VOBOOST_ANDROID_KEYSTORE_BASE64"))
        assertTrue(script.contains("VOBOOST_EMBEDDED_PRODUCT_PUBLIC_KEY_PEM must name the source-pinned product public PEM"))
        assertTrue(script.contains("ORG_GRADLE_PROJECT_debuggable"))
        assertFalse(script.contains("ORG_GRADLE_PROJECT_voboostDaemonGatewayEnabled"))
        assertTrue(script.contains("ANDROID_HOME is required"))
        assertFalse(script.contains("\$HOME"))
        assertTrue(script.contains("clean buildRelease --no-daemon -PvoboostUnsignedRelease=true"))
        assertTrue(script.contains("cmp \"\$tmp/first.apk\""))
        assertTrue(script.contains("unexpectedly produced a signed APK"))
        assertTrue(build.contains("isMinifyEnabled = true"))
        assertTrue(build.contains("signingConfig = if (unsignedRelease) null"))
        assertTrue(build.contains("Product public key must be an exact Ed25519 SPKI"))
        assertTrue(build.contains("Packaged product release-catalog root differs from explicit input"))
        assertTrue(build.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(build.contains("Unsigned preparation requires a non-debuggable build"))
        assertFalse(build.contains("voboostDaemonGatewayEnabled"))
        assertFalse(script.contains("voboostKeystorePassword="))
    }
}
