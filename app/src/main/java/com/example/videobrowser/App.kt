package com.example.videobrowser

import android.app.Application
import android.util.Log
import java.io.File
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaCaptureCoordinator.installServiceWorkerClient()
        // Initialize the separately installed Python helper + yt-dlp runtime off the UI thread.
        YtDlpExtractor.warmUp(this)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(Date().toString() + "\n" + Log.getStackTraceString(e))
            } catch (x: Exception) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
