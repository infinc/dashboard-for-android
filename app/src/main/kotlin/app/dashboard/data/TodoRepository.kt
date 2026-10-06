package app.dashboard.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** Todo 1 件。 */
@Serializable
data class TodoItem(val id: Long, val text: String, val createdAt: Long)

/**
 * Todo カードの中身（filesDir/todo.json）。ダッシュボードで足して、チェックを押すと消える。
 * 設定ではなく利用者のデータなので、プリセットを切り替えても変わらない。
 */
class TodoRepository(context: Context) {

    private val file = File(context.filesDir, "todo.json")
    private val tmpFile = File(context.filesDir, "todo.json.tmp")
    private val lock = Any()

    private val state = MutableStateFlow(load())

    /** 古い順。 */
    val items: StateFlow<List<TodoItem>> get() = state

    fun add(text: String) {
        val t = text.trim().replace('\n', ' ').take(MAX_TEXT)
        if (t.isEmpty()) return
        update { list ->
            val now = System.currentTimeMillis()
            (list + TodoItem(maxOf(now, (list.maxOfOrNull { it.id } ?: 0) + 1), t, now)).takeLast(MAX_ITEMS)
        }
    }

    fun done(id: Long) = update { list -> list.filterNot { it.id == id } }

    private fun update(mutate: (List<TodoItem>) -> List<TodoItem>) = synchronized(lock) {
        val next = mutate(state.value)
        state.value = next
        runCatching {
            tmpFile.writeText(Http.json.encodeToString(ListSerializer, next))
            if (!tmpFile.renameTo(file)) {
                file.writeText(tmpFile.readText())
                tmpFile.delete()
            }
        }.onFailure { Log.w(TAG, "todo.json の保存に失敗", it) }
    }

    private fun load(): List<TodoItem> = runCatching {
        if (file.exists()) Http.json.decodeFromString(ListSerializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "TodoRepository"
        const val MAX_ITEMS = 50
        const val MAX_TEXT = 120
        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(TodoItem.serializer())
    }
}
