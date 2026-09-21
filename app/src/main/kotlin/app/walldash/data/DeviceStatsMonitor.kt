package app.walldash.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

/**
 * 端末自身の状態。外部通信は一切しない。
 *
 * 壁掛けで常時充電する運用のため、充電が外れたことに気づけるのが主目的。
 */
class DeviceStatsMonitor(private val context: Context) {

    private val cpu = CpuMonitor()
    private val traffic = TrafficMonitor()

    fun snapshot(): DeviceStats {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE

        val storage = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes to stat.totalBytes
        }.getOrElse { 0L to 0L }

        val memory = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem to info.totalMem
        }.getOrElse { 0L to 0L }

        val (rxBps, txBps) = traffic.sample()

        return DeviceStats(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTemperatureC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null,
            cpuPercent = cpu.sample(),
            storageFreeBytes = storage.first,
            storageTotalBytes = storage.second,
            memoryAvailableBytes = memory.first,
            memoryTotalBytes = memory.second,
            rxBitsPerSec = rxBps,
            txBitsPerSec = txBps,
        )
    }
}
