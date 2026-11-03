package com.raza.ai.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.raza.ai.core.ContactMatcher
import com.raza.ai.core.ContactResolver
import com.raza.ai.core.ContactResult
import com.raza.ai.core.CountryCodes
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

/** Looks names up in the phone's own contacts. Needs the READ_CONTACTS permission; reads nothing until asked. */
class AndroidContactResolver(private val ctx: Context) : ContactResolver {
    override fun resolve(name: String): ContactResult {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ContactResult.NoPermission
        val rows = ArrayList<Pair<String, String>>()
        val like = "%" + name.trim().replace("%", "").replace("_", "") + "%"
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf(like),
                null,
            )?.use { c ->
                while (c.moveToNext() && rows.size < 200) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    rows.add(n to num)
                }
            }
        } catch (e: SecurityException) {
            return ContactResult.NoPermission
        }
        // The SQL LIKE only narrows the search; ContactMatcher decides who is meant.
        return if (rows.isEmpty()) ContactResult.NotFound else ContactMatcher.pick(name, rows)
    }
}

/** Calling code of the SIM country, used only as the default for numbers saved without a country code. */
class AndroidRegion(private val ctx: Context) {
    fun callingCode(): String {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        return CountryCodes.fromIso(tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)
    }
}
