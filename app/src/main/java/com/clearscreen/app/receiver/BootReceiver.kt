package com.clearscreen.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clearscreen.app.service.KeepAliveForegroundService
import com.clearscreen.app.util.PermissionHelper

/**
 * 开机与用户解锁广播接收器：
 * 在系统开机完成或用户解锁屏幕时触发，唤醒前台通知与保活服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        Log.i(TAG, "收到系统广播: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT -> {
                // 如果用户已经开启了无障碍权限，自动启动前台保活服务
                if (PermissionHelper.isAccessibilityServiceEnabled(context)) {
                    Log.i(TAG, "已授权无障碍服务，自动恢复前台守护进程")
                    KeepAliveForegroundService.startService(context)
                }
            }
        }
    }
}
