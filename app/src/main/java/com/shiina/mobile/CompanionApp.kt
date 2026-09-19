package com.shiina.mobile

import android.app.Application
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.di.AppContainer

class CompanionApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppDebugServer.start()
        AppDebugServer.log("SYSTEM", "CompanionApp onCreate started")
        container = AppContainer(this)
        AppDebugServer.log("SYSTEM", "AppContainer initialized successfully")
    }
}
