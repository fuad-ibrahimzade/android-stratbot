package com.stratbot.app

import android.app.Application
import com.stratbot.app.data.Services
import com.stratbot.app.exchange.ServicesHolder
import com.stratbot.app.runner.RunManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Services.init(this)
        ServicesHolder.keyProvider = { ex -> Services.keys.key(ex) }
        ServicesHolder.secretProvider = { ex -> Services.keys.secret(ex) }
        RunManager.init(this)
    }
}