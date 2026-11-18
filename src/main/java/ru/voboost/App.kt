package ru.voboost

import android.app.Application

/** Process owner of shared app logging. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.init(this, level = BuildConfig.LOG_LEVEL)
        ClusterScheduler.schedule(context = this)
        OtaScheduler.schedule(this)
    }
}
