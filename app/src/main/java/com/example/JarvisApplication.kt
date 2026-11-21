package com.example

import android.app.Application
import android.util.Log
import com.example.data.JarvisDatabase
import com.example.data.JarvisRepository

class JarvisApplication : Application() {
    companion object {
        var instance: JarvisApplication? = null
            private set
    }

    val database by lazy { JarvisDatabase.getDatabase(this) }
    val repository by lazy { JarvisRepository(database.jarvisDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // JARVIS live-call link: foreground service long-polls the PC call
        // server; incoming JARVIS calls produce the native ringing UI.
        // Never let call wiring take the app down with it.
        try {
            com.example.call.JarvisCallService.start(this)
        } catch (t: Throwable) {
            Log.w("JarvisApplication", "call service start failed", t)
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JarvisApplication", "Caught uncaught exception in thread ${thread.name}", throwable)
            if (throwable is OutOfMemoryError || throwable is StackOverflowError) {
                try {
                    defaultHandler?.uncaughtException(thread, throwable)
                } catch (t: Throwable) {
                    Log.e("JarvisApplication", "Error in default uncaught exception handler", t)
                }
            }
        }
    }
}
