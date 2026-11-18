package ru.voboost

import android.content.Context
import java.io.File

/**
 * Android path resolution for daemon-contract architecture.
 *
 * Application-owned OTA transaction and log data is confined to the app zone
 * `/data/user/0/ru.voboost` (equivalent to `/data/data/ru.voboost`, one
 * inode). The app zone contains app-private OTA state and bounded logs; no
 * daemon-owned path is exposed through this API. Uninstall initiation moved
 * to the machine Settings host and the daemon socket.
 *
 * Production code constructs this with an Android [Context]; unit tests use the
 * internal [Paths] constructor with an explicit base directory to avoid
 * needing a Context.
 *
 * @param appZone App zone base directory all paths resolve from
 */
class Paths internal constructor(
    /**
     * App zone base directory.
     * /data/user/0/ru.voboost (equivalent to /data/data/ru.voboost)
     */
    val appZone: File,
) {
    /**
     * Production constructor: resolve the app zone from the Android [Context].
     *
     * @param context Android application context
     */
    constructor(context: Context) : this(context.dataDir)

    /** App-private OTA transactions; no daemon path is exposed through this API. */
    val otaDir: File
        get() = File(appZone, "ota")

    /** Exact current OTA transaction identity. */
    val otaTransactionFile: File
        get() = File(appZone, "ota-transaction")

    /**
     * Application log files directory.
     * /data/data/ru.voboost/logs/
     */
    val logsDir: File
        get() = File(appZone, "logs")
}
