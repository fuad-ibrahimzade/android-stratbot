package com.stratbot.app.data

import android.app.Application
import android.content.Context

/** Lazily wired singletons. */
object Services {
    @Volatile private var app: Application? = null
    @Volatile private var settingsRef: Settings? = null
    @Volatile private var keysRef: Keys? = null
    @Volatile private var strategiesRef: StrategyStore? = null
    @Volatile private var runsRef: RunStore? = null

    fun init(application: Application) {
        if (app != null) return
        synchronized(this) {
            app = application
            settingsRef = Settings(application)
            keysRef = Keys(application)
            strategiesRef = StrategyStore(application)
            runsRef = RunStore(application)
            strategiesRef?.seedIfEmpty()
        }
    }

    val ctx: Context get() = app ?: throw IllegalStateException("Services not initialised")
    val settings: Settings get() = settingsRef ?: throw IllegalStateException("Services not initialised")
    val keys: Keys get() = keysRef ?: throw IllegalStateException("Services not initialised")
    val strategies: StrategyStore get() = strategiesRef ?: throw IllegalStateException("Services not initialised")
    val runs: RunStore get() = runsRef ?: throw IllegalStateException("Services not initialised")
}