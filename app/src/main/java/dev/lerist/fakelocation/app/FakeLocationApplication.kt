package dev.lerist.fakelocation.app

import android.app.Application
import dev.lerist.fakelocation.app.di.AppGraph

class FakeLocationApplication : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph.create(this)
    }
}
