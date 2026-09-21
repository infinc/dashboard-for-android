# Walldash の構造と、どこを触ると何が動くか

実機（Lenovo TB-X306F / Android 10 / API 29）と接続した状態で、コードと動作の両方を
読んだうえでまとめたもの。「あるコードを変更したら他のものも変わってしまう」箇所を
はっきりさせることを目的にしている。

---

## 1. 全体の形

Walldash は **Android アプリの中に HTTP サーバーを立て、自分の WebView でそれを見ている**。
つまり UI は Android の View ではなく、`assets/web` の HTML/CSS/JS で出来ている。

```
┌─ Android プロセス (app.walldash) ───────────────────────────────┐
│                                                                 │
│  MainActivity ──── WebView ──HTTP──▶ 127.0.0.1:8080             │
│   ・全画面 / 消灯防止                      ▲                     │
│   ・バックライト輝度の制御                 │                     │
│   ・walldash:// を拾う（ブラウズ・通知）   │                     │
│                                            │                     │
│  DashboardService (前面サービス) ──────────┘                     │
│   ・DashboardServer (Ktor CIO)  ← ルーティングと API             │
│   ・各 Repository の定期取得ループ（15 秒ごとに声をかける）       │
│                                                                 │
│  ConfigStore ── filesDir/config.json  ← 設定の唯一の保管場所      │
└─────────────────────────────────────────────────────────────────┘
          ▲                                     ▲
          │ adb forward tcp:8080                │ 外向き HTTPS のみ
      PC のブラウザ                    Open-Meteo / 気象庁 / 防災科研 /
      （/settings）                    RSS / Cloudflare Worker / Spotify
```

開発時の検証端末。UI の寸法と使える CSS はこの機種を基準に決めている:

| 項目 | 値 |
|---|---|
| 端末 | Lenovo TB-X306F、Android 10（API 29） |
| 画面 | 物理 800x1280 / density 160 → 横向きで **CSS 1280x800** |
| WebView | **Chrome 81 相当**（2020 年 4 月） |
| メモリ | 1.8 GB |
| 待受 | `127.0.0.1:8080`（既定。LAN 公開は明示的に有効化したときだけ `0.0.0.0`） |

地点・SSID・連携先などの実際の設定値は端末内の `filesDir/config.json` にあり、
リポジトリには含まれない。

---

## 2. ファイルの役割と依存

### Kotlin 側

| ファイル | 役割 | ここを変えると影響が出る先 |
|---|---|---|
| `data/Models.kt` | **全データ構造の定義**。`Config` / `DisplayConfig` / `DeviceState` など | **最も影響範囲が広い**。`@Serializable` なので JSON の形がそのまま変わり、`config.json`・`/api/state`・`/api/settings` の応答・`dashboard.js`・`settings.js` が同時に影響を受ける |
| `data/ConfigStore.kt` | `config.json` の読み書き。保存のたび `configVersion` を +1。`sanitize*` で値域を固定 | `sanitizeDisplay` の許可リスト（`ALLOWED_LAYOUTS`・`ACCENT_PATTERN`）を通らない値は**黙って既定値に戻る**。設定画面に選択肢を足すときはここも直す |
| `server/DashboardServer.kt` | ルーティング、静的配信、認証ガード、マスク処理 | `buildState()` の分岐が「LAN 未認証には何を返さないか」。項目を足したらここでマスクの要否を決める |
| `server/Auth.kt` | PIN の PBKDF2 ハッシュ、セッション、loopback 判定、試行回数制限 | `isLoopback` は CSRF 判定にも使われる |
| `MainActivity.kt` | 全画面 WebView、輝度、`walldash://` の処理 | 輝度は `DisplayConfig` を 3 秒ごとに読み直して当て直している（`configWatcher`）。JS ブリッジは持たない |
| `DashboardService.kt` | 前面サービス。サーバー起動と定期取得ループ | `onCreate` で**真っ先に** `startForeground()` を呼ぶ。重い初期化を前に置くと 5 秒制限で落ちる（実機で実際に落ちた経緯あり） |
| `data/*Repository.kt` | 各データ源。自前の取得間隔を持つ | サービスは 15 秒ごとに `refreshIfDue()` を呼ぶだけ。1 つ失敗しても他は止まらない |
| `LauncherMode.kt` | `HomeAlias` の有効・無効を `PackageManager` で切り替え | 再起動後に画面を前面へ戻せるかが変わる |

### Web 側（`assets/web`）

| ファイル | 役割 |
|---|---|
| `index.html` | ダッシュボードの DOM。カードは `<main>` 直下の `<section class="card c-xxx sN" id="cardXxx">` |
| `css/tokens.css` | 色・角丸・余白の変数。**Chrome 81 で使えない CSS の一覧も書いてある** |
| `css/dashboard.css` | カードの見た目と**素の列幅** |
| `js/dashboard.js` | 2 秒ごとに `/api/state` を取得して全カードを描画。列幅の詰め直しもここ |
| `settings.html` / `css/settings.css` / `js/settings.js` | 設定画面（左メニュー + 面） |
| `js/icons.js` `js/words.js` `js/hamster.js` | 天気アイコン / 単語辞書 / 回し車 |

---

## 3. 「ここを変えると、あれも変わる」一覧

作業前にいちばん見ておきたい箇所。

### 3-1. `DisplayConfig` に項目を足すとき（例: 新しいカード）

`Models.kt` の `DisplayConfig` に `showFoo` を足すだけでは出ない。**5 か所**が連動する。

1. `Models.kt` … `DisplayConfig` に `showFoo: Boolean = true`
2. `index.html` … `<section class="card c-foo sN" id="cardFoo">` を追加
3. `dashboard.js` の `applyConfig()` … `map` に `cardFoo: cfg.display.showFoo`
4. `dashboard.js` の `SPAN_BASE` … `cardFoo` の素の幅
5. `settings.html` … メニュー項目（`<i class="dot" data-w="showFoo">`）と面、
   面の中に `<input type="checkbox" data-w="showFoo">`

抜けたときの症状:
- 3 を忘れる → 設定で切っても消えない
- 4 を忘れる → 幅が既定の 8 列になる（他のカードの幅がずれる）
- 5 を忘れる → 設定画面から触れない

### 3-2. カードの幅を変えるとき ← **いちばん間違えやすい**

幅の定義が **CSS と JS の 2 か所**にある。

- `css/dashboard.css` の `.sN` / `.c-hourly` / `.c-spotify` / `body.layout-*` / `@media (orientation: portrait)`
- `js/dashboard.js` の `SPAN_BASE` / `SPAN_LAYOUT` / `SPAN_PORTRAIT`

**実際に効いているのは JS 側**（`packCards()` がインラインの `grid-column` で上書きするため）。
CSS だけ直しても見た目は変わらない。CSS 側は JS が動かなかったときの保険として残してある。

二重に持っている理由: 実機の WebView（Chrome 81）が
`getComputedStyle().gridColumnEnd` に `"span N"` を返さず、CSS から読み取れないため。
（最初は CSS から読む実装にしたが、全カードが既定値の 8 列になって崩れた。）

### 3-2b. アクセント色は JS の SVG にも効く

`--accent` を見ているのは CSS だけではない。`dashboard.js` が組み立てる SVG
（時間別予報の折れ線・面・降水確率の棒、Wi-Fi の折れ線、CPU の面）は
半透明の塗りが要るため、`accentRgb` から `accent(alpha)` / `accentLight()` で
色を作っている。`applyConfig()` が設定の色で `accentRgb` と CSS 変数
`--accent-rgb` の両方を入れ替える。

**SVG の中に色を直接書かないこと。** 書くとアクセント色を変えたときにそこだけ
取り残される（実際に時間別予報と Wi-Fi と CPU の 3 か所がそうなっていた）。

アクセント色に**追従しない**色は 3 つある。いずれも意味が色に紐づいているため:

- 週間予報の気温バーと凡例（`linear-gradient(90deg, #4DD4FF, #FFB347)`）… 寒色→暖色の温度スケール
- 画面上端のごく淡い光（`body::before`）
- 防災・Spotify・今日の単語のカード枠（琥珀／緑／赤の役割色）

### 3-2c. カードごとの見せ方の設定

`DisplayConfig` には「どのカードを出すか」に加えて「そのカードをどう見せるか」も入っている。

| キー | 効く場所 |
|---|---|
| `clockAlign` `clockDateFormat` | 時計カード。揃えは CSS の `.c-clock.al-*`、日付は `renderClock()` |
| `weatherFields` | 天気カードの数値の並び。キーは `dashboard.js` の `WX_FIELDS` と一致必須 |
| `disasterShowTyphoon` `disasterShowVolcano` | `renderDisaster()` が配列を空にする（署名も変わるので再描画される） |
| `disasterShowKmoni` | CSS の `.c-disaster.no-kmoni` と、`kmoniTick()` の早期 return（通信も止まる） |
| `hourlyMode` | `renderHourly()`。凡例・軸ラベル・棒の高さの倍率が同時に変わる |
| `spotifyShowControls` `spotifyShowProgress` | 両方切ると下段ごと消え、`.sp-wrap.big` で絵と曲名が大きくなる |
| `wifiShowGlobe` | CSS の `.c-wifi.no-globe` |
| `showHamster` | `window.Hamster.setVisible()`（`hamster.js`）。消すとタイマーも止まる |

**真偽値の設定を足すときは、`settings.html` のチェックボックスに `data-w="キー名"` を
書くだけでよい。** `settings.js` の `displayPatch()` が `input[data-w]` を全部拾って
`display` に入れるので、JS 側に足す場所は無い。
選択肢（`select`）と配列（`weatherFields`）だけは `displayPatch()` と `render()` の
両方に 1 行ずつ足す必要がある。

値域は `ConfigStore.sanitizeDisplay()` が固定する。許可リストに無い値は黙って既定へ戻るので、
選択肢を増やしたら `ALLOWED_*` も直すこと。

### 3-2d. ブラウズのお気に入り

お気に入りは `Config.browser.favorites` に入り、他の設定と同じ `config.json` に保存される。
UI は Web ではなく **`MainActivity` の素の View**（ブラウズ画面はダッシュボードとは別の
WebView を重ねたものなので、ダッシュボードの HTML/CSS/JS は一切関係しない）。

操作は 2 つ。ツールバー右の **☆／★** が登録・解除、その右の **☰** がメニュー。

| 部品 | 場所 |
|---|---|
| ☆／★ の切り替え | `MainActivity.toggleFavorite()` / `updateFavStar()` |
| 登録時の名前入力 | `promptAddFavorite()`（既定値は `favoriteTitle()`、全選択で開く） |
| ☰ メニュー | `showBrowserMenu()`（`PopupMenu`。項目 id は `MENU_*`） |
| 一覧（確認・移動・削除） | `showFavoritesDialog()` / `favoriteRow()` / `confirmRemoveFavorite()` |
| 一覧から開く | `openFavorite()` |
| 名前の既定値 | `WebChromeClient.onReceivedTitle` → `cleanTitle()` → `favoriteTitle()` |
| 保存と整形 | `ConfigStore.updateFavorites()`（`sanitizeBrowser` を必ず通す） |

注意点:

- **URL 欄は焦点が残っていると更新されない。** `syncUrlField()` が
  「打っている途中の文字を消さない」ために `hasFocus()` を見ているため、
  お気に入りから開くときは `openFavorite()` が焦点を外してから読み込ませる。
  これをしないと、選んだ先に移動したのに URL 欄が前の入力のまま残る。
- **ページ内で履歴だけ書き換えるサイトは `onPageStarted` を通らない。**
  YouTube は動画を選んでもページを読み直さず `history.pushState` で URL を変えるだけなので、
  URL 欄も ★ も前のページのままになっていた。`doUpdateVisitedHistory()` がこの通知を受けるので、
  URL が変わったときの処理は `onBrowserUrlChanged()` にまとめ、
  読み込み開始・読み込み完了・履歴の書き換えの 3 経路から呼んでいる
  （同じ URL で重ねて呼ばれても先頭で弾く）。
- 一覧は削除しても開いたままにする（`refresh()` が中身だけ作り直す）。
  続けて整理できるようにするため。

- **題名は `WebView.getTitle()` では当てにならない。** `onPageFinished` の時点ではまだ
  入っていないことがあり、実機で Yahoo!ニュースを登録したらホスト名になった。
  `WebChromeClient.onReceivedTitle` を主に使い、`onPageFinished` は保険にしている。
- `<title>` の無いページでは WebView が URL をそのまま題名として渡してくる。
  `cleanTitle()` がそれを空として扱い、ホスト名に落とす。
- **`favorites` は `PublicConfig` に載せていない。** どこを見ているかは生活の様子が出るうえ、
  端末の前で登録して端末の前で使うものなので、外に出す経路を作っていない。
  設定画面から編集する機能を足すなら、LAN 未認証へのマスク（3-8）を先に決めること。
- 登録すると `configVersion` が +1 される（3-4）。ダッシュボード側は再描画が走るが、
  ブラウズ中は `webView.onPause()` で止めてあるので実害はない。

### 3-2e. 通知音

音はすべて Web Audio API でその場で合成している（音源ファイルは持たない）。
設定は `Config.notifications`（`display` とは別の入れ物）。

| キー | 効く場所 |
|---|---|
| `disasterSound` | `dashboard.js` の `chime()` が先頭で弾く。**バナー表示は止めない** |
| `chargingSound` | `checkChargingChange()`。覚えの更新は続けてから弾く（次の抜き差しを取りこぼさないため） |
| `volume` | **端末のメディア音量そのもの**を一時的に動かして作る（下記） |

#### 音量は WebAudio のゲインではなく端末の音量を動かす

最初は WebAudio の GainNode で絞る実装にしたが、**端末の主音量が小さいと通知も小さくなる**ため
やめた。壁掛けで欲しいのは逆で、「端末の音量が小さくても通知だけは設定した大きさで鳴る」こと。

いまの流れ:

1. `dashboard.js` の `withNoticeVolume(soundMs, play)` が
   `walldash://volume?ms=…` を呼ぶ（鳴る長さ + 余白）
2. `MainActivity.holdNoticeVolume()` が**今の音量を覚えてから**
   `AudioManager.STREAM_MUSIC` を設定値まで動かす
3. `VOLUME_SETTLE_MS`（180ms）置いてから音を鳴らす。
   **待たずに鳴らすと変更前の音量で出る**（音量変更は非同期に起きるため）
4. 保持時間が切れたら `restoreNoticeVolume()` が元の音量へ戻す

注意点:

- **元の音量は最初の 1 回だけ覚える。** 鳴っている最中に重ねて呼ばれたときに上書きすると、
  戻す先が「通知のために上げた音量」になってしまう。
- タイマーの鳴動は 2 秒ごとに保持を掛け直す。止めたときは `ms=0` でその場で戻す。
- `onPause()` と `onDestroy()` でも戻す。鳴っている最中に他のアプリへ移られると上げたままになる。
- **副作用**: 端末の音量そのものを動かすので、ブラウズで動画を見ている最中に通知が鳴ると
  その 1〜2 秒だけ動画の音量も変わる。設定画面にもその旨を書いてある。
- マナーモードや DND では `setStreamVolume` が拒まれることがある。`runCatching` で握って
  ログだけ出す（音が出せなくても表示は続ける）。

タイマーの鳴動だけは切る設定を置いていない（自分で時間を決めて鳴らすものなので、
鳴らないと用をなさない）。音量は同じ経路を通る。

**音を足すときは `withNoticeVolume()` を通すこと。** 直接 `tone()` / `beep()` を呼ぶと
音量設定が効かず、端末のそのときの音量で鳴る。

### 3-2f. 気象庁の警報エンドポイントは移動済み

**使うのは `bosai/warning/data/r8/{府県コード}.json`。**
以前の `bosai/warning/data/warning/{府県コード}.json` は**気象庁が更新を止めている**。
2026-09-21 に確認した時点で、全国どの府県も `last-modified` が 2026-05-28 のまま止まっており、
実際には大雨警報が出ている日に 4 か月前の濃霧注意報を壁に出し続けていた。
200 が返り JSON も正しい形なので、**取得の失敗としては検知できない**のが厄介な点。

他の気象庁データ（`quake/data/list.json`、`typhoon/data/targetTc.json`、
`forecast/data/forecast/*.json`）は同じ日に更新されていたので、止まっているのは警報だけ。

新しい方は形がまったく違う:

| | 旧 `data/warning/` | 新 `data/r8/` |
|---|---|---|
| 最上位 | オブジェクト 1 つ | **文書の配列**（大雨・土砂災害・風・波・雷…） |
| 区域 | `areaTypes[0].areas[].code` | `warning.class10Items[].areaCode` |
| 種別 | `areas[].warnings[]` | `class10Items[].kinds[]` |
| 見出し | 1 つ | **文書ごとに 1 つ** |

そのため `DisasterRepository` は**区域ごとに全文書を束ね直す**。
束ねないと最後に読んだ 1 種類しか出ない。

見出しは**最新の文書 1 本だけ**を出す。5 本つなぐと 3 行を超えてカードから溢れ、
下に並ぶ区域の行が押し出された。どの種別が出ているかは区域の行が伝える。

**土砂災害警戒情報（`dataTypeCode = VPWW56`）は種別コードの体系が別。**
`WARNING_KINDS` で引くと別の警報名になりかねないので、この文書のコードは引かず
「土砂災害」とだけ出す（`kindLabel()`）。ある県の実データでは 2 つの区域に
それぞれ 29 と 09 が入っており、気象庁のページは前者を「土砂災害注意報」、
後者を「土砂災害警報」と出していた。とはいえ観測できたのはこの 1 例だけなので、
警報か注意報かまでは決めつけない。

エンドポイントを疑うときは、**気象庁の警報ページをブラウザで開いて通信を見る**のが早い
（`https://www.jma.go.jp/bosai/warning/#area_type=offices&area_code={府県コード}`。
既定の東京都なら `130000`）。
今回もそれで `data/r8/` が分かった。

### 3-3. 設定を保存するとき（`display` は「まるごと差し替え」）

`ConfigPatch.display` は差分ではなく**置き換え**。`applyPatch` が `patch.display ?: c.display`
としているため、一部のキーだけ送ると**残りが既定値に戻る**。

そのため `settings.js` は、どの面の「保存」を押しても画面上の**すべての** `input[data-w]` から
`display` を組み立て直している（`displayPatch()`）。面ごとに部分更新すると、
別の面で変えたチェックが保存のたびに巻き戻る。

### 3-3b. モックサーバーの設定は手で合わせる

`tools/mock-server.mjs` の `config` は Kotlin の `Config` を手書きで写したもの。
**欠けていても動いてしまう**（設定画面が未設定を既定値で補うため）ので、
`Models.kt` に項目を足したらここにも足すこと。
足さないと、実機では出るはずの違いがモックでは見えないまま UI を詰めることになる。

### 3-4. `configVersion` の役割

`ConfigStore.update()` が保存のたびに +1 する。これを見ている場所が **2 つ**ある。

- `dashboard.js` の `render()` … 値が変わったときだけ `applyConfig()` を呼ぶ（= 再描画と列の詰め直し）
- `MainActivity.configWatcher` … 3 秒ごとに読み、変わっていたら輝度を当て直す

`configVersion` を止めると、**設定画面で保存しても壁に反映されなくなる**。

### 3-5. Chrome 81 の制約

`tokens.css` の冒頭に書いてある通り、以下は**使えない**:

`inset` ショートハンド / **flexbox の `gap`** / `:is()` / `:has()` / `color-mix()` /
コンテナクエリ / `aspect-ratio`

使えるもの: CSS 変数、CSS Grid（`gap` 含む、ただし `grid-gap` も併記）、`clamp()`、
`backdrop-filter`、`position: sticky`。

PC のブラウザでは動くのに実機だけ崩れる不具合の大半がこれ。
デバッグビルドは `WebView.setWebContentsDebuggingEnabled(true)` が入っているので、
PC から `chrome://inspect` で実機の WebView を直接見られる。

### 3-6. ポート 8080 は固定

変えると **WebView の参照先・`adb forward`・ブラウザの URL・Spotify の Redirect URI** が
同時に壊れる。設定項目にしていないのはそのため。

### 3-7. 設定画面は 2 か所から開かれる

- PC のブラウザ（`adb forward` 経由の localhost）
- タブレットの歯車 → `index.html` の `<iframe id="panelFrame">` が `/settings` を読む（96vw x 94vh ≒ 1229x752）

どちらも loopback 扱いなので認証は不要。
**iframe から開かれているときは `window.top !== window.self`** で、Spotify の認可は
枠内に出せない（`frame-ancestors` で拒まれる）ため `walldash://browser` に投げている。
設定画面のレイアウトを変えるときは、この 1229x752 に収まるかを必ず確認すること。

### 3-8. LAN 未認証へのマスク

`DashboardServer.buildState()` が、認証の無い LAN からのアクセスに対して
SSID・IP・正確な緯度経度・LINE メモ本文・Spotify の再生内容を伏せる。
`DeviceState` に項目を足したら、ここで伏せるかどうかを決める必要がある。

---

## 4. ダッシュボードの並び（24 列グリッド）

`<main>` は `grid-template-columns: repeat(24, 1fr)` で、カードは列数を `span` で指定する。
12 分割だと「7 列の 65%」のような幅が作れず、時間別予報を狭めた残りに Spotify を
置けなかったため 24 分割になっている（クラス名の `.s3` 等は 12 分割時代のまま）。

`balanced` レイアウトでは 4 行ちょうどに収まる:

| 行 | カード（列数） | 計 |
|---|---|---|
| 1 | 時刻 8 ・ 天気 8 ・ 防災 8 | 24 |
| 2 | LINE メモ 10 ・ 時間別予報 9 ・ Spotify 5 | 24 |
| 3 | Wi-Fi 6 ・ 端末 10 ・ ニュース 8 | 24 |
| 4 | 週間予報 12 ・ タイマー 6 ・ 今日の単語 6 | 24 |

カードを隠すと、CSS の自動配置は残りを詰めるだけで**幅は縮めない**ので行の右端が空く。
`dashboard.js` の `packCards()` が、行ごとの合計をちょうど 24 列にしてから
`grid-column` を上書きしてこれを埋める（配分は元の幅に比例、端数は最大剰余法）。

---

## 5. 設定画面の構造

左にメニュー、右に選んだ項目の設定だけを出す。ダッシュボードのカードは 1 枚 = 1 項目で、
そのカードに効く設定を同じ面に置いている。メニュー項目の左の点が、
いまそのカードを表示しているかを表す。

```
全体   … レイアウト・配色 / 画面の明るさ / 場所
カード … 時刻 天気 防災 LINE メモ 時間別予報 Spotify
         Wi-Fi 端末状態 ニュース 週間予報 タイマー 今日の単語
端末   … ホームアプリ / ネットワーク（LAN 公開）
```

選択中の面は `location.hash` に入るので、`/settings#weather` で直接開ける。

**「表示する」と「取得する」は別物**（防災・ニュース・LINE メモ・Spotify）:
「表示する」はカードを出すかどうか、「取得する」は通信するかどうか。
カードだけ隠して取得は続けたい、という使い方があるため分けてある。

保存の経路は入力の秘匿性で分かれている:

| 入口 | 扱うもの |
|---|---|
| `POST /api/settings` | `display` `units` `location` `refresh` `disaster` `feed` |
| `POST /api/memo` | メモの中継先と端末トークン（トークンは読み出し経路が無い） |
| `POST /api/spotify` | Spotify の Client ID（`refreshToken` は認可の経路でしか入らない） |
| `POST /api/lan` | PIN と LAN 公開（PIN は PBKDF2 + ソルト、平文は保持しない） |
| `POST /api/device` | ホームアプリ登録 |

LINE メモと Spotify の面だけは「表示トグル（`display`）」と「固有の設定」で
入口が違うため、保存時に 2 本の POST を順に投げている。
