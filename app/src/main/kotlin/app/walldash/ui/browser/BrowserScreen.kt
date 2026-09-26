package app.walldash.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walldash.data.ConfigStore
import app.walldash.data.Favorite
import app.walldash.ui.common.WdIcons
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu

private const val HOME_URL = "https://www.google.com"

/**
 * ダッシュボードの上に重ねる簡易ブラウザ。閉じたら WebView ごと破棄する
 * （2 画面分の WebView を抱え続けないため。見ていたページが壁に残らない効果もある）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(config: ConfigStore, startUrl: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val favorites by config.flow.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var currentTitle by remember { mutableStateOf("") }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var fieldFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun onUrlChanged(url: String) {
        if (url == currentUrl) return
        currentUrl = url
        // 新しいページの題名はまだ来ていない。前のページの題名を残すと登録名が別ページのものになる。
        currentTitle = ""
        if (!fieldFocused) field = TextFieldValue(url)
    }

    fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val target = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            !q.contains(' ') && q.contains('.') -> "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
        focus.clearFocus()
        webView?.loadUrl(target)
    }

    val saved = favorites.browser.favorites
    val favoritable = currentUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val starred = favoritable != null && saved.any { it.url == favoritable }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize().background(Wd.Bg).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(Wd.Surface).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolText("ホーム", onClose)
            ToolIcon(WdIcons.Back, "戻る") { webView?.takeIf { it.canGoBack() }?.goBack() }
            ToolIcon(WdIcons.Forward, "進む") { webView?.takeIf { it.canGoForward() }?.goForward() }
            ToolIcon(WdIcons.Refresh, "再読み込み") { webView?.reload() }
            TextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                placeholder = { Text("URL または検索語", color = Wd.Text3, fontSize = 14.tu) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Wd.Text, fontSize = 14.tu),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(field.text) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Wd.Bg, unfocusedContainerColor = Wd.Bg,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                // 触れたら今の URL を全選択する（打った文字が URL の後ろに繋がらないように）
                modifier = Modifier.weight(1f).padding(start = 6.dp).onFocusChanged {
                    fieldFocused = it.isFocused
                    if (it.isFocused) field = field.copy(selection = TextRange(0, field.text.length))
                },
            )
            Box(
                Modifier.size(46.dp).clickable {
                    when {
                        favoritable == null -> toast("このページは登録できません")
                        starred -> {
                            config.updateFavorites { list -> list.filterNot { it.url == favoritable } }
                            toast("お気に入りから外しました")
                        }
                        saved.size >= ConfigStore.MAX_FAVORITES -> toast("お気に入りは ${ConfigStore.MAX_FAVORITES} 件までです")
                        else -> adding = favoritable
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (starred) "★" else "☆",
                    fontSize = 20.tu,
                    color = when {
                        starred -> Wd.Amber
                        favoritable == null -> Wd.Text3
                        else -> Wd.Text2
                    },
                )
            }
            Box {
                ToolIcon(WdIcons.Menu, "メニュー") { menuOpen = true }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("お気に入り") }, onClick = { menuOpen = false; listOpen = true })
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                // WebView を無効にしている端末では作れない。落とさずに理由を出す
                runCatching { WebView(ctx) }.getOrElse {
                    return@AndroidView android.widget.TextView(ctx).apply {
                        text = "この端末では WebView（Android System WebView）が使えないため、ブラウズを開けません。"
                        setTextColor(Wd.Text2.toArgb())
                        textSize = 15f
                        setPadding(48, 48, 48, 48)
                    }
                }.apply {
                    setBackgroundColor(Wd.Bg.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onUrlChanged(url)

                        override fun onPageFinished(view: WebView, url: String) {
                            onUrlChanged(url)
                            if (currentTitle.isBlank()) currentTitle = cleanTitle(view.title, url)
                        }

                        // 履歴だけ書き換えるサイト（YouTube など）は onPageStarted を通らないので、ここでも拾う
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onUrlChanged(url)
                    }
                    // 題名は onPageFinished の時点ではまだ入っていないことがあるので、こちらを主に使う
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            currentTitle = cleanTitle(title, view.url.orEmpty())
                        }
                    }
                    loadUrl(startUrl?.takeIf { it.startsWith("http") } ?: HOME_URL)
                    webView = this
                }
            },
        )
    }

    LaunchedEffect(startUrl) {
        startUrl?.takeIf { it.startsWith("http") }?.let { url -> webView?.takeIf { it.url != url }?.loadUrl(url) }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply { stopLoading(); destroy() }
            webView = null
        }
    }

    adding?.let { url ->
        NameDialog(
            title = "お気に入りに追加",
            message = url,
            initial = favoriteTitle(currentTitle, url),
            confirm = "追加",
            onDismiss = { adding = null },
            onConfirm = { name ->
                config.updateFavorites { list -> list + Favorite(url = url, title = name.ifEmpty { favoriteTitle(currentTitle, url) }) }
                adding = null
                toast("お気に入りに追加しました")
            },
        )
    }

    if (listOpen) {
        FavoritesDialog(
            config = config,
            favorites = saved,
            onOpen = { url ->
                listOpen = false
                focus.clearFocus()
                field = TextFieldValue(url)
                webView?.loadUrl(url)
            },
            onDismiss = { listOpen = false },
        )
    }
}

/**
 * お気に入りの一覧。名前を押すとそのページへ移動し、右端の ⋮ から名前の変更と削除ができる。
 * 変更しても一覧は開いたままにする（続けて整理できるように）。
 */
@Composable
private fun FavoritesDialog(config: ConfigStore, favorites: List<Favorite>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<Favorite?>(null) }
    var removing by remember { mutableStateOf<Favorite?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("お気に入り") },
        text = {
            if (favorites.isEmpty()) {
                Text("まだありません。ページを開いて ☆ を押すと登録できます。", color = Wd.Text2, fontSize = 13.tu)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp).widthIn(min = 420.dp)) {
                    items(favorites, key = { it.url }) { fav ->
                        FavoriteRow(fav, onOpen = { onOpen(fav.url) }, onRename = { renaming = fav }, onRemove = { removing = fav })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )

    renaming?.let { fav ->
        NameDialog(
            title = "名前を変更",
            message = fav.url,
            initial = fav.title,
            confirm = "変更",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                config.updateFavorites { list ->
                    list.map { if (it.url == fav.url) it.copy(title = name.ifEmpty { fav.title }) else it }
                }
                renaming = null
            },
        )
    }

    removing?.let { fav ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("お気に入りから削除") },
            text = { Text("${fav.title}\n${fav.url}", fontSize = 13.tu) },
            confirmButton = {
                TextButton(onClick = {
                    config.updateFavorites { list -> list.filterNot { it.url == fav.url } }
                    removing = null
                }) { Text("削除", color = Wd.Red) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun FavoriteRow(fav: Favorite, onOpen: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 4.dp, top = 11.dp, bottom = 11.dp, end = 10.dp)) {
            Text(fav.title, fontSize = 15.tu, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fav.url, color = Wd.Text3, fontSize = 11.5f.tu, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(WdIcons.More, "操作", tint = Wd.Text2, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("削除", color = Wd.Red) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

/** 名前の入力。既定値を全選択した状態で開き、そのまま確定すれば既定値のまま、打てば置き換わる。 */
@Composable
private fun NameDialog(
    title: String,
    message: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, color = Wd.Text3, fontSize = 12.tu, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(value.text.trim()) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(focusRequester),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun ToolText(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.tu)
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp, 40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Wd.Text, modifier = Modifier.size(22.dp))
    }
}

/** <title> の無いページでは URL がそのまま題名として来るので、空として扱う。 */
private fun cleanTitle(raw: String?, url: String): String {
    val t = raw?.trim().orEmpty()
    return if (t.isEmpty() || t == url || t.startsWith("http://") || t.startsWith("https://")) "" else t
}

/** 登録名の既定値。題名が無ければホスト名にする（空欄にはしない）。 */
private fun favoriteTitle(title: String, url: String): String =
    title.trim().ifEmpty { runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url }
