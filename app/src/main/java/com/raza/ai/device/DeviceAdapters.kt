package com.raza.ai.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.raza.ai.core.Connectivity
import com.raza.ai.core.DeviceInfo
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Real network state: a validated internet connection, not just "Wi-Fi is on". */
class AndroidConnectivity(private val ctx: Context) : Connectivity {
    override fun isOnline(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

class AndroidDeviceInfo(private val ctx: Context) : DeviceInfo {
    override fun timeText(): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.now())

    override fun dateText(): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(LocalDate.now())

    override fun batteryText(): String? {
        val intent: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val pct = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (charging) "$pct percent and charging" else "$pct percent"
    }
}
