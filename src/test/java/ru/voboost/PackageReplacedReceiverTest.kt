package ru.voboost

import android.app.job.JobScheduler
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PackageReplacedReceiverTest {
    @Test
    fun replacementRestoresOnePersistedOtaJob() {
        val context = RuntimeEnvironment.getApplication()
        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val pending = scheduler.allPendingJobs
        assertEquals(setOf(OtaScheduler.JOB_ID, ClusterScheduler.REPAIR_JOB_ID), pending.map { it.id }.toSet())
        val ota = pending.single { it.id == OtaScheduler.JOB_ID }
        assertEquals(OtaScheduler.JOB_ID, ota.id)
        assertEquals(OtaJobService::class.java.name, ota.service.className)
    }
}
