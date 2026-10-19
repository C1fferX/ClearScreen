package com.clearscreen.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.clearscreen.app.data.db.AppDatabase
import com.clearscreen.app.data.repository.StatsRepository

class ClearScreenApp : Application() {

    lateinit var repository: StatsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = AppDatabase.getInstance(this)
        repository = StatsRepository(db.interceptDao())

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "清屏后台保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持清屏服务在后台稳定运行，实时统计拦截次数"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    companion object {
        const val CHANNEL_ID = "clearscreen_keep_alive_channel"
        lateinit var instance: ClearScreenApp
            private set
    }
}
