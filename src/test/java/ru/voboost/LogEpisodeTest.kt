package ru.voboost

import org.junit.Assert.assertEquals
import org.junit.Test

class LogEpisodeTest {
    @Test fun independentTransactionsDoNotShareWaitSuppression() {
        val records = mutableListOf<String>()
        val episode = LogEpisode { records += it }
        repeat(1000) { episode.waiting("peer-unavailable", "transaction-one:peer") }
        episode.waiting("peer-unavailable", "transaction-two:peer")
        assertEquals(listOf("waiting: peer-unavailable", "waiting: peer-unavailable"), records)
    }

    @Test fun repeatedWaitsAreSilentUntilReasonChangesOrRecovery() {
        val records = mutableListOf<String>()
        val episode = LogEpisode { records += it }
        repeat(1000) { episode.waiting("daemon-unavailable") }
        episode.waiting("setup-required")
        episode.recovered()
        episode.recovered()
        episode.waiting("daemon-unavailable")
        assertEquals(listOf("waiting: daemon-unavailable", "waiting: setup-required", "recovered", "waiting: daemon-unavailable"), records)
    }
}
