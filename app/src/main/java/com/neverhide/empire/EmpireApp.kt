package com.neverhide.empire

import android.app.Application
import com.neverhide.empire.core.EmpireBackgroundService
import com.neverhide.empire.core.CrashLogger

/** Application singleton. Starts the persistent background service on launch. */
class EmpireApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        EmpireBackgroundService.start(this)
    }
}
