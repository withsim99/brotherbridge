package com.example

import android.app.Application
import com.example.printer.spooler.PrintSpoolerEngine

class PrintBridgeApp : Application() {

    lateinit var spoolerEngine: PrintSpoolerEngine
        private set

    override fun onCreate() {
        super.onCreate()
        spoolerEngine = PrintSpoolerEngine(applicationContext)
    }
}
