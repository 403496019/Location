package dev.lerist.fakelocation.app

import android.content.Context
import dev.lerist.fakelocation.app.di.AppGraph

val Context.appGraph: AppGraph
    get() = (applicationContext as FakeLocationApplication).appGraph
