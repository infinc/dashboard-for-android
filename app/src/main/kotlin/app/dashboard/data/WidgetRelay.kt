package app.dashboard.data

import app.dashboard.i18n.L
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.InetAddress
import java.net.URI

// 自作カードの「中継」（source = "relay"）。
//
// カードはふだん「URL から取る」と同じように [WidgetDef.url] の JSON を取って見せる。
// その応答に "relay" があれば、それは「この端末から代わりに取ってきてほしい」という指示で、
// 書かれた先へ順に取りに行き、本文をまとめて "relay.result" へ POST で返す（[RelayReport]）。
// サーバー（Cloudflare Workers など）からは届かない、この端末の回線からしか見えないサイトを、サーバーが代わりに読むための仕組み。
//
// 指示する側は信頼できない前提で、ここで次を確かめる（[WidgetRelay.check]）:
// - 返す先（result）は https:// で、カードの URL と同じホストだけ（ヘッダーのトークンをよそへ渡さない）
// - 取りに行く先は https:// だけで、LAN・ループバックのアドレスには行かない（家の中の機器を外から触らせない）
// - 件数・長さ・本文の大きさに上限

/** 取りに行く 1 件。[form] があれば `application/x-www-form-urlencoded` の POST。 */
@Serializable
data class RelayRequest(
    val method: String = "GET",
    val url: String = "",
    val form: Map<String, String> = emptyMap(),
)

/** 応答の "relay"。[id] は返すときにそのまま付ける。 */
@Serializable
data class RelayJob(
    val id: String = "",
    val result: String = "",
    val requests: List<RelayRequest> = emptyList(),
)

/** 取ってきた 1 件。取れなかったときは [status] 0 で [error] に理由。 */
@Serializable
data class RelayResponse(
    val status: Int = 0,
    val body: String = "",
    val error: String? = null,
)

/** "relay.result" へ返す本文。 */
@Serializable
data class RelayReport(
    val id: String,
    val responses: List<RelayResponse>,
)

object WidgetRelay {

    const val MAX_REQUESTS = 16
    const val MAX_FORM_FIELDS = 32
    const val MAX_ID = 100
    private val ID = Regex("[A-Za-z0-9._:-]{1,$MAX_ID}")

    /** 応答の本文から指示を取り出す。"relay" が無い・null なら null（ふつうの取得）。形が違えば [IllegalArgumentException]。 */
    fun job(body: String): RelayJob? {
        val root = runCatching { Http.json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val relay = root["relay"] ?: return null
        if (relay !is JsonObject) return null
        return runCatching { Http.json.decodeFromJsonElement(RelayJob.serializer(), relay.jsonObject) }
            .getOrElse { throw IllegalArgumentException(L("中継の指示の形が違います", "The relay instruction is malformed")) }
    }

    /** 指示を確かめる。問題なければ null、だめなら理由。名前解決はしない（[isPrivateHost] は別に呼ぶ）。 */
    fun check(cardUrl: String, job: RelayJob): String? {
        if (!ID.matches(job.id)) return L("中継の id の形が違います", "The relay id is invalid")
        val result = uri(job.result) ?: return L("返す先の URL の形が違います", "The relay result URL is invalid")
        if (result.scheme?.lowercase() != "https") return L("返す先は https:// にしてください", "The relay result must be https://")
        val cardHost = uri(cardUrl)?.host?.lowercase()
        if (cardHost == null || result.host?.lowercase() != cardHost) return L("返す先はカードの URL と同じホストにしてください", "The relay result must be on the card URL's host")
        if (job.requests.isEmpty()) return L("取りに行く先がありません", "No relay requests")
        if (job.requests.size > MAX_REQUESTS) return L("取りに行く先が多すぎます（$MAX_REQUESTS 件まで）", "Too many relay requests (max $MAX_REQUESTS)")
        job.requests.forEach { r ->
            val method = r.method.uppercase()
            if (method != "GET" && method != "POST") return L("取り方は GET か POST だけです", "Only GET and POST are allowed")
            if (method == "GET" && r.form.isNotEmpty()) return L("GET にフォームは付けられません", "GET can't carry a form")
            if (r.form.size > MAX_FORM_FIELDS || r.form.any { (k, v) -> k.length > 100 || v.length > 1000 }) return L("フォームが大きすぎます", "The form is too large")
            val u = uri(r.url) ?: return L("取りに行く URL の形が違います", "A relay URL is invalid")
            if (u.scheme?.lowercase() != "https") return L("取りに行く先は https:// だけです", "Relay URLs must be https://")
            val host = u.host ?: return L("取りに行く URL にホストがありません", "A relay URL has no host")
            if (looksLocal(host)) return L("家の中（LAN）の機器には中継しません", "Relaying to LAN devices is not allowed")
        }
        return null
    }

    /** 名前を引いて、LAN・ループバック・リンクローカルのアドレスを含むなら true（IO スレッドで呼ぶ）。 */
    fun isPrivateHost(host: String, resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName): Boolean =
        resolve(host).any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || isUniqueLocal(it) }

    /** 名前を引かなくても分かる LAN の書き方（localhost・.local・IP の直書き）。 */
    fun looksLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val literal = Regex("^[0-9.]+$").matches(h) || h.contains(':')
        if (!literal) return false
        val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return true
        return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || isUniqueLocal(addr)
    }

    private fun isUniqueLocal(a: InetAddress): Boolean = a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc

    private fun uri(s: String): URI? = runCatching { URI(s.trim()) }.getOrNull()?.takeIf { it.host != null }
}
