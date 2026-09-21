package app.walldash.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.serialization.Serializable

/**
 * LINE から届いたメモを中継サーバーから取得する。
 *
 * LINE Messaging API は公開 HTTPS の Webhook を要求するが、このタブレットは NAT の内側にあり、
 * 既定で loopback しか待ち受けない。したがってタブレットは着信を受けず、
 * 中継（Cloudflare Worker）に対して外向きにポーリングするだけにしている。
 * 取得は必ず端末トークン付きで行う。トークンが無ければ URL を知る者が全員メモを読めてしまう。
 */
class MemoRepository(
    private val client: HttpClient,
    private val configStore: ConfigStore,
) {

    @Volatile
    var state: MemoState = MemoState()
        private set

    private var lastAttemptAt = 0L

    suspend fun refreshIfDue() {
        val config = configStore.get().memo
        if (!config.enabled || config.endpoint.isBlank() || config.token.isNullOrBlank()) return
        val interval = config.pollIntervalMs.coerceIn(10_000, 600_000)
        if (System.currentTimeMillis() - lastAttemptAt < interval) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val config = configStore.get().memo
        if (config.endpoint.isBlank() || config.token.isNullOrBlank()) return
        try {
            val dto: MemoDto = client.get(config.endpoint) {
                header("Authorization", "Bearer ${config.token}")
            }.body()
            val items = dto.toItems()
            state = MemoState(
                items = items,
                text = items.firstOrNull()?.text,
                senderName = items.firstOrNull()?.senderName,
                receivedAt = items.firstOrNull()?.receivedAt ?: 0L,
                fetchedAt = System.currentTimeMillis(),
                lastError = null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "メモの取得に失敗", e)
            // 直前のメモは残したまま、エラーだけ添える（壁を空白にしない）
            state = state.copy(
                fetchedAt = System.currentTimeMillis(),
                lastError = e.message ?: e::class.java.simpleName,
            )
        }
    }

    /**
     * 中継からの応答。
     * [memos] が本体だが、旧版の中継は最新 1 件を平坦なフィールドで返すので両方受ける。
     */
    @Serializable
    private data class MemoDto(
        val memos: List<MemoItemDto>? = null,
        val text: String? = null,
        val senderName: String? = null,
        val receivedAt: Long = 0,
    ) {
        fun toItems(): List<MemoItem> {
            memos?.let { list ->
                return list.mapNotNull { it.toItem() }
            }
            val single = text?.takeIf { it.isNotBlank() } ?: return emptyList()
            return listOf(
                MemoItem(
                    id = "legacy-$receivedAt",
                    text = single,
                    senderName = senderName,
                    receivedAt = receivedAt,
                )
            )
        }
    }

    @Serializable
    private data class MemoItemDto(
        val id: String? = null,
        val text: String? = null,
        val senderName: String? = null,
        val receivedAt: Long = 0,
    ) {
        fun toItem(): MemoItem? {
            val body = text?.takeIf { it.isNotBlank() } ?: return null
            return MemoItem(
                // 識別子が無い中継でも、受信時刻で十分に区別できる
                id = id ?: "t-$receivedAt",
                text = body,
                senderName = senderName,
                receivedAt = receivedAt,
            )
        }
    }

    private companion object { const val TAG = "MemoRepository" }
}
