package ru.voboost

import android.annotation.SuppressLint
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SchedulerRecoveryReceiverTest {
    @Test
    fun recoveryRestoresOnePersistedJobIdempotently() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = SchedulerRecoveryReceiver()
        repeat(2) {
            val result = deliverOrdered(context, receiver)
            assertEquals(JobScheduler.RESULT_SUCCESS to "scheduler-ready", result)
        }
        val job = context.getSystemService(JobScheduler::class.java).allPendingJobs.single { it.id == OtaScheduler.JOB_ID }
        assertEquals(OtaScheduler.JOB_ID, job.id)
        assertEquals(OtaJobService::class.java.name, job.service.className)
        assertTrue(job.isPersisted)
        assertTrue(job.isPeriodic)
        assertEquals(OtaScheduler.PERIOD_MS, job.intervalMillis)
    }

    @Test
    fun schedulingFailureCannotAcknowledgeReady() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        shadowOf(scheduler).failOnJob(OtaScheduler.JOB_ID)
        assertEquals(
            JobScheduler.RESULT_FAILURE to "scheduler-failed",
            deliverOrdered(context, SchedulerRecoveryReceiver()),
        )
        assertEquals(listOf(ClusterScheduler.REPAIR_JOB_ID), scheduler.allPendingJobs.map { it.id })
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // This host fixture runs only API 28.
    private fun deliverOrdered(
        context: Context,
        receiver: SchedulerRecoveryReceiver,
    ): Pair<Int, String?> {
        var result: Pair<Int, String?>? = null
        context.registerReceiver(receiver, IntentFilter(SchedulerRecoveryReceiver.ACTION))
        try {
            context.sendOrderedBroadcast(
                Intent(SchedulerRecoveryReceiver.ACTION),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        result = resultCode to resultData
                    }
                },
                null,
                -1,
                null,
                null,
            )
            shadowOf(Looper.getMainLooper()).idle()
            return checkNotNull(result)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun unknownOrMissingActionCannotSchedule() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        val receiver = SchedulerRecoveryReceiver()
        for (action in listOf(null, Intent.ACTION_BOOT_COMPLETED, "foreign.RECONCILE_SCHEDULER")) {
            receiver.onReceive(context, Intent(action))
        }
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun recoveryManifestRequiresPlatformSignaturePermission() {
        // Android performs component permission admission before invoking
        // onReceive. Direct unit calls intentionally bypass that framework
        // boundary; verify its production manifest rather than claiming
        // Robolectric's broadcast dispatcher emulates system_server.
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val receivers = document.getElementsByTagName("receiver")
        val receiver =
            (0 until receivers.length).map(receivers::item).single {
                it.attributes.getNamedItemNS(ANDROID, "name").nodeValue == ".SchedulerRecoveryReceiver"
            }
        assertEquals("true", receiver.attributes.getNamedItemNS(ANDROID, "exported").nodeValue)
        assertEquals(PERMISSION, receiver.attributes.getNamedItemNS(ANDROID, "permission").nodeValue)
        assertFalse((0 until receiver.childNodes.length).any { receiver.childNodes.item(it).nodeName == "intent-filter" })
        val permissions = document.getElementsByTagName("uses-permission")
        assertFalse(
            (0 until permissions.length).any {
                permissions.item(it).attributes.getNamedItemNS(ANDROID, "name").nodeValue == PERMISSION
            },
        )
        // The courier itself is an ordinary application, not granted this
        // platform signature permission. No run-time permission prompt or
        // adb development grant can turn it into a privileged sender.
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            RuntimeEnvironment.getApplication().checkSelfPermission(PERMISSION),
        )
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
        private const val PERMISSION = "android.permission.BIND_JOB_SERVICE"
    }
}
