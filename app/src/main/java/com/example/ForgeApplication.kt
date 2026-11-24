package com.example

import android.app.Application
import com.example.core.di.ForgeContainer

/**
 * Application class initializing the modular [ForgeContainer] dependency graph.
 */
class ForgeApplication : Application() {
    lateinit var container: ForgeContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ForgeContainer(this)
    }
}
