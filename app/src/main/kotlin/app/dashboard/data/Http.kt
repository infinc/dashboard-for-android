package app.dashboard.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * 全リポジトリで 1 つの HttpClient を共有する。
 * この端末はメモリが 1.8GB しかないため、取得先ごとにクライアントを作らない。
 */
object Http {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * 接続プールとスレッドの大もと。Ktor のエンジンと、OkHttp を直に使う取得（予定表の CalDAV・写真・船舶の WebSocket）が
     * これを newBuilder() して共有する（クライアントごとにプールとスレッドを持たない）。
     * タイムアウトはここで決めない。使う側がそれぞれ newBuilder() の後で決める（Ktor は HttpTimeout が決める）。
     */
    val okHttp: OkHttpClient by lazy { OkHttpClient() }

    /**
     * エンジンは OkHttp。Android エンジン（HttpURLConnection）は、タイムアウトで取得を打ち切るときに
     * 端末内蔵の okhttp が「Unbalanced enter/exit」を投げ、それ以降すべての取得が失敗し続ける（ネットに繋がらない状態になる）。
     */
    fun newClient(): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }
}
