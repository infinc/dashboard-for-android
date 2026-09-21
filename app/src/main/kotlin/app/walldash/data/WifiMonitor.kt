package app.walldash.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.Inet4Address

/**
 * Wi-Fi のリンク状態を監視する。通信は一切発生させない（実効スループット測定は行わない）。
 *
 * 取得経路が API レベルで分かれる:
 *  - API 31+ : ConnectivityManager のコールバックで受け取る NetworkCapabilities.transportInfo の WifiInfo
 *  - API 24-30: WifiManager.connectionInfo（API 31 で deprecated）
 *
 * SSID だけは権限に依存する。取れない場合も理由を [WifiState.ssidStatus] に入れて返し、
 * リンク速度・RSSI・帯域は常に表示できるようにする。
 */
class WifiMonitor(private val context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile private var callbackWifiInfo: WifiInfo? = null
    @Volatile private var currentNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
            currentNetwork = network
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                callbackWifiInfo = caps.transportInfo as? WifiInfo
            }
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) {
                currentNetwork = null
                callbackWifiInfo = null
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { Log.e(TAG, "NetworkCallback の登録に失敗", it) }
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    fun snapshot(): WifiState {
        val info = resolveWifiInfo() ?: return WifiState(
            connected = false,
            ssidStatus = ssidStatusWhenMissing(),
            updatedAt = System.currentTimeMillis(),
        )

        val rssi = info.rssi.takeIf { it != Int.MIN_VALUE && it < 0 }
        val rawSsid = info.ssid?.trim('"')
        val ssidUsable = !rawSsid.isNullOrBlank() &&
            rawSsid != WifiManager.UNKNOWN_SSID.trim('"') &&
            rawSsid != "<unknown ssid>" &&
            rawSsid != "0x"

        val frequency = info.frequency.takeIf { it > 0 }

        return WifiState(
            connected = true,
            ssid = if (ssidUsable) rawSsid else null,
            ssidStatus = if (ssidUsable) SsidStatus.OK else ssidStatusWhenMissing(),
            linkSpeedMbps = resolveLinkSpeed(info),
            rxLinkSpeedMbps = resolveRxLinkSpeed(info),
            txLinkSpeedMbps = resolveTxLinkSpeed(info),
            rssiDbm = rssi,
            signalLevel = rssi?.let(::normalizeSignalLevel) ?: 0,
            band = frequency?.let(::bandOf),
            frequencyMhz = frequency,
            ipAddress = resolveIpAddress(info),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun resolveWifiInfo(): WifiInfo? {
        callbackWifiInfo?.let { return it }
        // コールバックがまだ発火していないとき（起動直後）と、API 30 以下の通常経路。
        // API 31+ でも登録に失敗している可能性があるため、最後の手段として常に見に行く。
        @Suppress("DEPRECATION")
        return wifiManager.connectionInfo?.takeIf { it.isActuallyConnected() }
    }

    /**
     * connectionInfo は未接続でもオブジェクトを返すため、接続済みかどうかを自前で判定する。
     * 未接続時は networkId が -1 になり、RSSI も無効値（Int.MIN_VALUE や -127）になる。
     */
    private fun WifiInfo.isActuallyConnected(): Boolean =
        networkId != -1 || rssi in -126..-1

    /** 送信リンク速度が取れる端末ではそちらを優先する。いずれも Mbps。 */
    private fun resolveLinkSpeed(info: WifiInfo): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.txLinkSpeedMbps.takeIf { it > 0 }?.let { return it }
        }
        return info.linkSpeed.takeIf { it > 0 }
    }

    /*
     * 方向別のリンク速度は API 29 で追加されたもので、それ以前は方向をまとめた 1 つの値しか無い。
     * 取れない世代で linkSpeed を下り・上りの両方に流用すると、実際には別々の値を
     * 同じ数字として見せてしまうため、null のままにして UI 側で 1 行表示に落とす。
     */
    private fun resolveRxLinkSpeed(info: WifiInfo): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.rxLinkSpeedMbps.takeIf { it > 0 }
        } else {
            null
        }

    private fun resolveTxLinkSpeed(info: WifiInfo): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.txLinkSpeedMbps.takeIf { it > 0 }
        } else {
            null
        }

    /** RSSI を 0..4 に正規化する。API 30 以降は端末側の基準に従う。 */
    private fun normalizeSignalLevel(rssi: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val max = wifiManager.maxSignalLevel.coerceAtLeast(1)
            val level = wifiManager.calculateSignalLevel(rssi)
            (level * 4f / max).toInt().coerceIn(0, 4)
        } else {
            @Suppress("DEPRECATION")
            WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
        }

    private fun bandOf(frequencyMhz: Int): String = when {
        frequencyMhz >= 5925 -> "6GHz"
        frequencyMhz >= 4900 -> "5GHz"
        frequencyMhz >= 2400 -> "2.4GHz"
        else -> "不明"
    }

    private fun resolveIpAddress(info: WifiInfo): String? {
        currentNetwork?.let { network ->
            val props: LinkProperties? = connectivity.getLinkProperties(network)
            props?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let { return it.address.hostAddress }
        }
        @Suppress("DEPRECATION")
        val raw = info.ipAddress
        if (raw == 0) return null
        @Suppress("DEPRECATION")
        return "%d.%d.%d.%d".format(
            raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff
        )
    }

    /**
     * SSID が取れないときの理由を判定する。
     * UI 側はこのコードを見て「権限が必要」「位置情報サービスを ON に」などの案内を出す。
     */
    private fun ssidStatusWhenMissing(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            if (!granted(Manifest.permission.NEARBY_WIFI_DEVICES)) SsidStatus.PERMISSION_REQUIRED
            else SsidStatus.UNAVAILABLE

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            when {
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> SsidStatus.PERMISSION_REQUIRED
                !locationServicesEnabled() -> SsidStatus.LOCATION_SERVICES_OFF
                else -> SsidStatus.UNAVAILABLE
            }

        else ->
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) SsidStatus.PERMISSION_REQUIRED
            else SsidStatus.UNAVAILABLE
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun locationServicesEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }

    private companion object { const val TAG = "WifiMonitor" }
}
