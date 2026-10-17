package app.dashboard.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

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
     * エンジンは OkHttp。Android エンジン（HttpURLConnection）は、タイムアウトで取得を打ち切るときに
     * 端末内蔵の okhttp が「Unbalanced enter/exit」を投げ、それ以降すべての取得が失敗し続ける（ネットに繋がらない状態になる）。
     */
    fun newClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }
}
