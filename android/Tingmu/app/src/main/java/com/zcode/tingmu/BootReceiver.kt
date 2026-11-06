package com.zcode.tingmu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 听幕·厅开机自启。
 *
 *  Android 14+ 不允许从 BOOT_COMPLETED 直接启动 microphone 类型前台服务，
 *  所以走「开机 → 拉 HallActivity（有 SYSTEM_ALERT_WINDOW 权限即豁免后台启动限制）
 *  → Activity 里 startForegroundService → finish」的标准路径。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("hall_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", true)) return
        Log.i("TingmuHall", "BOOT → launch HallActivity")
        try {
            context.startActivity(
                Intent(context, HallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("boot", true)
            )
        } catch (e: Exception) {
            Log.w("TingmuHall", "boot launch fail", e)
        }
    }
}
