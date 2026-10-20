package app.dashboard.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * 家の中（LAN）の機器へ、平文の HTTP で GET する小さなクライアント（自作カード用）。
 *
 * `network_security_config.xml` は平文を localhost と強震モニタにしか許していないので、OkHttp では
 * Home Assistant（`http://homeassistant.local:8123`）やルーターの管理画面のような LAN の `http://` に届かない。
 * アプリ全体の平文を許すと、ほかの取得先が誤って平文になっても気づけなくなるので、**プライベートなアドレスだけ**に
 * 使うこのクライアントを別に持つ。名前を引いた結果がプライベートでなければ断り、引いたアドレスにそのままつなぐ
 * （引き直しで外のアドレスへ向けられる DNS rebinding を避ける）。
 *
 * HTTP/1.1・`Connection: close`・転送（3xx）はたどらない・本文は [MAX_BYTES] まで・chunked に対応。
 */
object LanHttp {

    class Response(val status: Int, val headers: Map<String, String>, val body: String)

    class NotPrivate(host: String) : IOException("not a private address: $host")

    const val MAX_BYTES = 1_048_576
    private const val TIMEOUT_MS = 10_000

    /** 平文で取りに行ってよいアドレスか（loopback・10/8・172.16/12・192.168/16・169.254/16・100.64/10・fc00::/7・fe80::/10）。 */
    fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        val b = a.address
        return when (a) {
            // 100.64.0.0/10 は CGNAT（Tailscale の端末もこの範囲）
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            // fc00::/7（ULA）
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    /** [host] を引き、すべてプライベートならその 1 つ目。外のアドレスが混じれば [NotPrivate]。 */
    fun resolvePrivate(host: String, resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName): InetAddress {
        val all = resolve(host.removePrefix("[").removeSuffix("]"))
        if (all.isEmpty() || !all.all(::isPrivate)) throw NotPrivate(host)
        return all.first()
    }

    /** `http://` の [url] へ GET。[headers] はそのまま送る。 */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = TIMEOUT_MS): Response {
        val uri = URI(url)
        require(uri.scheme.equals("http", ignoreCase = true)) { "http:// だけです" }
        val host = uri.host ?: throw IOException("no host: $url")
        val port = if (uri.port > 0) uri.port else 80
        val address = resolvePrivate(host)
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            val out = socket.getOutputStream()
            out.write(request(uri, headers).toByteArray(Charsets.UTF_8))
            out.flush()
            return read(socket.getInputStream())
        }
    }

    /** 要求の文。ヘッダーの値に改行が入っていたら落とす（ヘッダーを足されないように）。 */
    internal fun request(uri: URI, headers: Map<String, String>): String {
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = uri.host + if (uri.port > 0 && uri.port != 80) ":${uri.port}" else ""
        val sb = StringBuilder()
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(hostHeader).append("\r\n")
        sb.append("User-Agent: Dashboard (Android)\r\n")
        sb.append("Accept: application/json, text/plain, */*\r\n")
        sb.append("Accept-Encoding: identity\r\n")
        sb.append("Connection: close\r\n")
        headers.forEach { (k, v) ->
            val name = k.trim()
            if (name.isEmpty() || name.any { it == ':' || it == '\r' || it == '\n' } || v.any { it == '\r' || it == '\n' }) return@forEach
            sb.append(name).append(": ").append(v.trim()).append("\r\n")
        }
        sb.append("\r\n")
        return sb.toString()
    }

    /** 応答を読む（状態の行・ヘッダー・本文）。 */
    internal fun read(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw IOException("empty response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("bad status line: ${statusLine.take(60)}")
        val status = parts[1].toIntOrNull() ?: throw IOException("bad status: ${parts[1]}")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > 100) throw IOException("too many headers")
        }
        val bytes = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val n = headers["content-length"]!!.toLongOrNull() ?: throw IOException("bad content-length")
                if (n > MAX_BYTES) throw IOException("response too large")
                readExactly(input, n.toInt())
            }
            else -> readAll(input)
        }
        val charset = headers["content-type"]?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')
            ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return Response(status, headers, String(bytes, charset))
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) throw IOException("line too long")
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw IOException("unexpected end of body")
            off += r
        }
        return out
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r == -1) break
            out.write(buf, 0, r)
            if (out.size() > MAX_BYTES) throw IOException("response too large")
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("unexpected end of chunks")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                // 後ろのヘッダー（trailer）を空の行まで読み捨てる
                while (readLine(input)?.isNotEmpty() == true) Unit
                break
            }
            if (out.size() + size > MAX_BYTES) throw IOException("response too large")
            out.write(readExactly(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }
}
