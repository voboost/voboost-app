package ru.voboost

/** One owner-local wait episode; repeated polls do not produce records. */
internal class LogEpisode(private val emit: (String) -> Unit) {
    private var reason: String? = null
    private var owner: String? = null

    @Synchronized
    fun waiting(
        value: String,
        operation: String = "",
    ) {
        if (reason == value && owner == operation) return
        reason = value
        owner = operation
        emit("waiting: $value")
    }

    @Synchronized
    fun recovered() {
        if (reason == null) return
        reason = null
        owner = null
        emit("recovered")
    }
}
