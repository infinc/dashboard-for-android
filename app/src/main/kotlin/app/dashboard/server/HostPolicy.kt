package app.dashboard.server

import java.net.NetworkInterface

/**
 * 「この端末の設定サーバー自身」を指す宛先かどうか。サーバーの Host ヘッダーの検査（DNS rebinding 対策）と、
 * アプリ内ブラウザが設定サーバーを開かないようにする判定の両方で使う。
 *
 * - loopback（127.x・localhost・::1）と、端末のネットワークのアドレス（Wi-Fi・有線など。DHCP で変わっても毎回読み直す）
 * - [extraHosts] は将来の mDNS（dashboard.local など）や独自の名前のための追加分（設定の lan.extraHosts）
 * - 名前を DNS で引いて比べることはしない（引くこと自体が DNS rebinding の入口になるため）。比べるのは文字の一致だけ
 */
object HostPolicy {

    /** Host ヘッダー・URL の host から、ポートと [] を外して小文字にする。 */
    fun hostOf(value: String?): String? {
        val v = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.startsWith("[")) return v.substringAfter('[').substringBefore(']').substringBefore('%')
        // IPv6 をそのまま書いたもの（: が 2 つ以上）はポートが無い
        if (v.count { it == ':' } > 1) return v.substringBefore('%')
        return v.substringBefore(':')
    }

    fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1" ||
            (host.startsWith("127.") && host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } })

    /**
     * [host] がこの端末を指すか。[localAddresses] は端末のアドレス（テストでは差し替える）。
     */
    fun isSelf(
        host: String?,
        extraHosts: List<String> = emptyList(),
        localAddresses: () -> Set<String> = ::localAddresses,
    ): Boolean {
        val h = hostOf(host) ?: return false
        if (isLoopback(h)) return true
        if (extraHosts.any { hostOf(it) == h }) return true
        return h in localAddresses()
    }

    /** 端末のいまのアドレス（文字の形。IPv6 はスコープを外す）。 */
    fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%')?.lowercase() }
            .toSet()
    }.getOrDefault(emptySet())
}
