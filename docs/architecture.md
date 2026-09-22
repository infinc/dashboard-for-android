# Walldash の構造と、どこを触ると何が動くか

コードと実機の動作の両方を読んだうえでまとめたもの。「あるコードを変えたら他のものも変わる」箇所を
はっきりさせることを目的にしている。

---

## 1. 全体の形

タブレットの画面（カード・設定・ブラウズ）は **Jetpack Compose のネイティブ画面**。
データは常駐サービスが集めてプロセス内に持ち、画面はそれを直接読む（HTTP は通さない）。
アプリ内蔵の HTTP サーバーは、**PC や他の端末のブラウザから開く設定画面**のためだけにある。

```
┌─ Android プロセス (app.walldash) ───────────────────────────────────┐
│                                                                     │
│  MainActivity（Compose）                                            │
│   ├ DashboardScreen … カード 12 枚・フッター・通知バナー・ハムスター │
│   ├ SettingsPanel   … 設定（「全て保存」でまとめて保存）            │
│   └ BrowserScreen   … 簡易ブラウザ（WebView はここだけ）             │
│        ▲ 2 秒ごとに AppGraph.snapshot() を読む（DashboardViewModel）│
│                                                                     │
│  AppGraph（プロセスに 1 つ）                                        │
│   ├ ConfigStore（filesDir/config.json、StateFlow で変化を配る）      │
│   ├ Weather / Disaster / Feed / Memo / Spotify の各 Repository      │
│   ├ WifiMonitor / DeviceStatsMonitor                                │
│   ├ SettingsController（設定の書き換えの唯一の入口）                 │
│   ├ NoticePlayer（通知音の合成と、鳴らす間だけのメディア音量）       │
│   └ DashboardServer（Ktor CIO, :8080）… Web の設定画面と /api/*     │
│                                                                     │
│  DashboardService（前面サービス）… サーバー起動と定期取得の見回り    │
└─────────────────────────────────────────────────────────────────────┘
        ▲ adb forward tcp:8080（USB の PC）     ▲ 外向き HTTPS/HTTP のみ
        ▲ LAN（LAN 公開 ON のときだけ）          Open-Meteo / 気象庁 / 防災科研 /
                                                 RSS / Cloudflare Worker / Spotify
```

開発時の検証端末:

| 項目 | 値 |
|---|---|
| 端末 | Lenovo TB-X306F、Android 10（API 29） |
| 画面 | 横向きで 1280 x 800 dp（density 160） |
| メモリ | 1.8 GB |

地点・SSID・連携先などの実際の設定値は端末内の `filesDir/config.json` にあり、リポジトリには含まれない。

---

## 2. ファイルの役割

### Kotlin（`app/src/main/kotlin/app/walldash/`）

| ファイル | 役割 |
|---|---|
| `AppGraph.kt` | プロセスに 1 つだけ持つ部品の置き場。サービス・画面・サーバーが同じ実体を使う。`snapshot()` が画面と Web の設定画面が読む全データ |
| `SettingsController.kt` | 設定の書き換え。アプリの設定画面と `/api/*` の両方がここを通る（全て保存・PIN・LAN 公開・ホームアプリ・再取得） |
| `MainActivity.kt` | Compose の画面の重ね方、全画面化、消灯防止、明るさ（通常時／無操作時／通知中）、権限の確認 |
| `DashboardService.kt` | 前面サービス。サーバーを立て、15 秒（Spotify は 5 秒）ごとに各 Repository に声をかける |
| `BootReceiver.kt` / `LauncherMode.kt` | 起動・更新時のサービス起動 / ホームアプリ登録（`HomeAlias` の有効化） |
| `data/Models.kt` | **全データ構造**。`@Serializable` なので、変えると `config.json`・`/api/*`・Web の設定画面・モックが連動する |
| `data/Choices.kt` | アクセント色と通知音の候補。アプリと Web の設定画面の両方がこの一覧を使う |
| `data/ConfigStore.kt` | `config.json` の読み書き、`configVersion` の +1、`sanitize*`（値域の固定）、`flow`（StateFlow） |
| `data/*Repository.kt` | 各データ源。自分の取得間隔とバックオフを持つ。1 つ失敗しても他は止まらない |
| `data/JmaAreaLocator.kt` | 緯度経度 → 気象庁の市町村（3-8） |
| `data/Words.kt` | 今日の単語の辞書（同梱） |
| `server/DashboardServer.kt` / `server/Auth.kt` | Web の設定画面の配信、API、PIN・セッション・loopback 判定・CSRF |
| `sound/NoticePlayer.kt` | 通知音の合成（AudioTrack）とメディア音量の一時変更（3-4） |
| `ui/theme/Theme.kt` | 色・アクセント色（`LocalAccent`）・文字サイズ（`tu`・`vh`） |
| `ui/common/Common.kt` | カードの枠（`WdCard`）、アイコン（SVG パスから作る）、数字の桁揃え |
| `ui/dashboard/DashboardViewModel.kt` | 2 秒ごとの取得、防災通知・充電の検知、タイマー、強震モニタとジャケット画像 |
| `ui/dashboard/DashboardScreen.kt` | カードの並べ方（3-2）、フッター、通知バナー、焼き付き防止のずらし |
| `ui/dashboard/SimpleCards.kt` / `ChartCards.kt` / `RichCards.kt` | 各カード |
| `ui/dashboard/WeatherIcon.kt` / `Hamster.kt` / `GlobeData.kt` | 天気アイコン、回し車のハムスター、Wi-Fi カードの地球儀の海岸線 |
| `ui/settings/SettingsScreen.kt` / `SettingsWidgets.kt` | アプリの設定画面と部品 |
| `ui/browser/BrowserScreen.kt` | ブラウズとお気に入り（3-9） |

### Web（`app/src/main/assets/web/`）— PC・LAN から開く設定画面だけ

| ファイル | 役割 |
|---|---|
| `settings.html` / `js/settings.js` / `css/settings.css` | 設定画面（左メニュー + 面、左下に「全て保存」） |
| `login.html` | LAN から PIN でログインする画面 |
| `css/tokens.css` | 色などの変数 |

---

## 3. 「ここを変えると、あれも変わる」一覧

### 3-1. 新しいカードを足すとき

1. `Models.kt` … `DisplayConfig` に `showFoo: Boolean = true`
2. `DashboardScreen.kt` … `Slot` に `FOO(幅)` を足し、`shown` と `Card()` の `when` に 1 行ずつ
3. `SettingsScreen.kt` … `Pane` に項目を足し、`PaneContent()` に面を書く
4. `settings.html` … メニュー（`<i class="dot" data-w="showFoo">`）と面、面の中に `<input type="checkbox" data-w="showFoo">`
5. `tools/mock-server.mjs` … `config.display` に `showFoo`

Web 側の真偽値は `data-w` を書くだけで保存対象になる（`settings.js` の `collect()` が全部拾う）。
`select` と配列は `collect()` と `render()` の両方に 1 行ずつ足す。

### 3-2. カードの幅と並び

幅は `DashboardScreen.kt` の `Slot` の数値（24 列中いくつ分か）だけで決まる。

| 行 | カード（列数） |
|---|---|
| 1 | 時刻 8 ・ 天気 8 ・ 防災 8 |
| 2 | LINE メモ 10 ・ 時間別予報 9 ・ Spotify 5 |
| 3 | Wi-Fi 6 ・ 端末 10 ・ ニュース 8 |
| 4 | 週間予報 12 ・ タイマー 6 ・ 今日の単語 6 |

非表示のカードが空けた列は `pack()` が同じ行に残ったカードへ比例配分する（端数は最大剰余法）。
縦向きと、横でも幅が 840dp 未満の端末では 2 列（時間別予報だけ全幅）にして縦にスクロールさせる。

### 3-3. アクセント色

候補は `Choices.kt` の `Accents.ALL`。`ConfigStore.sanitizeDisplay()` は候補に無い色を既定に戻す。
画面では `LocalAccent` として配り、グラフの線・メーター・ドラムの帯・設定画面のスイッチなどが追従する。
Web の設定画面は `/api/settings` の `choices.accents` から選択肢を作るので、HTML は触らなくてよい。

アクセント色に追従しない色（意味が色に紐づくもの）: 週間予報の気温バー（寒色→暖色）、
防災・Spotify・今日の単語・LINE メモの枠と見出し（琥珀／緑／赤／紫の役割色）。

### 3-4. 通知音

音はすべて `NoticePlayer` がその場で合成する（音源ファイルは持たない）。音色は `Choices.kt` の `Tones.ALL`、
どれを鳴らすかは `NotificationConfig`（`disasterTone` / `chargingTone` / `timerTone`）。

| 音 | 鳴らす所 |
|---|---|
| 防災の切り替わり | `DashboardViewModel.checkDisaster()` |
| 充電の抜き差し | `checkCharging()`（抜いたときは音の高さの並びを逆にする） |
| タイマー | `tickTimer()` → `NoticePlayer.startRing()`（2 秒ごと、約 40 秒） |
| 試聴 | アプリの設定画面、Web の設定画面（`POST /api/sound/preview`、タブレットから鳴る） |

**音量は合成のゲインではなく端末のメディア音量で作る。** ゲインで絞ると端末の音量が小さいときに通知まで
小さくなるため。鳴らす直前に元の音量を 1 回だけ覚えてメディア音量を設定値へ動かし、
180ms 待ってから鳴らし（音量の変更は非同期に効く）、鳴り終わったら戻す。
重ねて鳴ったときは戻す時刻を延ばすだけ（戻す先を「通知のために上げた音量」にしないため）。
`MainActivity.onPause()` でも必ず戻す。

副作用として、ブラウズで動画を見ている最中に通知が鳴ると、その 1〜2 秒だけ動画の音量も変わる。

### 3-5. 設定の保存（「全て保存」）

どちらの設定画面も、各面の変更を溜めて 1 回にまとめて送る。

| | アプリ | Web |
|---|---|---|
| 溜める所 | `SettingsScreen.kt` の `Draft` | 画面上の入力そのもの（`collect()` が組み立てる） |
| 未保存の判定 | `Draft` が保存済みの値と違う | `collect()` が読み込み直後と違う |
| 閉じるとき | 「未保存の変更があります。」のダイアログ | ブラウザの `beforeunload` の確認 |
| 保存 | `SettingsController.saveAll()` | `POST /api/settings`（中身は `SaveAllRequest`） |

- `display` などは差分ではなく**置き換え**。だから送る側は常に全項目を組み立てる
- LINE メモのトークンは書き込み専用。入力されたときだけ送り、読み出す経路は無い
- その場で効かせる操作（ホームアプリ登録・PIN・LAN 公開・Spotify の連携と解除・再取得）は「全て保存」に含めない
- 取得先に関わる値（地点・単位・防災・ニュース・メモ・Spotify）が変わったら、`saveAll()` が待たずに取り直す
- 値域は `ConfigStore.sanitize*` が固定する。許可リストに無い値は黙って既定に戻る

### 3-6. 設定の変化の伝わり方

`ConfigStore.update()` が保存のたびに `configVersion` を +1 し、`flow`（StateFlow）に流す。

- 画面（`DashboardScreen` / `SettingsPanel` / `BrowserScreen`）は `flow` を購読して描き直す
- `MainActivity` は `configVersion` の変化で明るさを当て直す
- Web の設定画面は読み込み時と保存の応答で最新を受け取る。アプリ側の設定画面は、手元で編集していなければ
  他の端末からの保存に追従する

### 3-7. 気象庁の警報エンドポイントは移動済み

**使うのは `bosai/warning/data/r8/{府県コード}.json`。**
以前の `bosai/warning/data/warning/{府県コード}.json` は**気象庁が更新を止めている**。
2026-09-21 に確認した時点で、全国どの府県も `last-modified` が 2026-05-28 のまま止まっており、
実際には大雨警報が出ている日に 4 か月前の濃霧注意報を壁に出し続けていた。
200 が返り JSON も正しい形なので、**取得の失敗としては検知できない**のが厄介な点。

新しい方は形がまったく違う:

| | 旧 `data/warning/` | 新 `data/r8/` |
|---|---|---|
| 最上位 | オブジェクト 1 つ | **文書の配列**（大雨・土砂災害・風・波・雷…） |
| 区域 | `areaTypes[0].areas[].code` | 一次細分 `warning.class10Items[].areaCode`、市町村 `warning.class20Items[].areaCode` |
| 種別 | `areas[].warnings[]` | `class20Items[].kinds[]`（一次細分も同じ形） |
| 見出し | 1 つ | **文書ごとに 1 つ** |

`DisasterRepository` は**全文書から対象の市町村の行を集めて束ねる**（3-8）。束ねないと最後に読んだ 1 種類しか出ない。

見出しは**文書 1 本だけ**を出す（5 本つなぐとカードから溢れる）。選ぶのは、その市町村に出ている文書のうち
**段階がいちばん高いもの**（同じ段階なら新しいもの）。

**種別コードは、どの文書から来たものも 1 つの表（`WARNING_KINDS`）で引く。**
表は気象庁の警報ページ（`bosai/warning/`）のスクリプトが持つ対応表を写したもの
（2026-09-21 確認、全 33 件）で、名前もページの表示に合わせてある。

| 種別 | レベル２ | レベル３ | レベル４ | レベル５ |
|---|---|---|---|---|
| 大雨 | 10 注意報 | 03 警報 | **43 危険警報** | 33 特別警報 |
| 土砂災害 | 29 注意報 | 09 警報 | **49 危険警報** | 39 特別警報 |
| 高潮 | 19 注意報 | 08 警報 | **48 危険警報** | 38 特別警報 |

それ以外（風・雪・波・雷など）は段階の付かない従来の名前。洪水（04/18）は表に無く、
気象庁のページでは河川ごとの氾濫情報として別に扱われている（Walldash は未対応）。
表に無いコードは推測せず「コードNN」と出し、赤（警報扱い）にする。

エンドポイントを疑うときは、**気象庁の警報ページをブラウザで開いて通信を見る**のが早い
（`https://www.jma.go.jp/bosai/warning/#area_type=offices&area_code={府県コード}`。既定の東京都なら `130000`）。

### 3-8. 警報・注意報の対象は「天気の地点がある市町村」

防災の地域は設定に持たない。天気の地点（`Config.location`）の緯度経度から、
気象庁の市町村区分（`area.json` の `class20s`）を自動で決める。
天気と防災を別々に選ばせると、地点を変えたときに片方だけ古いまま残るため。

決め方は気象庁の警報ページにある「現在地」ボタンと同じ（`JmaAreaLocator`）:

1. `common/const/class20relm.json`（市町村ごとの外接矩形、約 140KB）で、点を含む候補に絞る
2. 候補の境界 `common/const/geojson/class20s/{コード}.json`（1 件数 KB）で、点が内側にあるかを偶奇判定する
3. どれの内側にもなければ（海岸線や湖の上の点）、矩形の中心がいちばん近い市町村。
   ただし 30km より遠ければ見つからない扱い（国外の地点で日本のどこかを返さないため）

府県予報区は `area.json` の親を `class20s → class15s → class10s → offices` とたどって決める。
結果は `filesDir/disaster-area.json` に、どの緯度経度について決めたかと一緒に保存し、地点が変わったときだけ決め直す。

一次細分区域（北部・南部など）は使わない。区域内のどこか 1 か所に出ていれば区域全体に出るので、
自分の市町村より強く出ることがある（実際に、市町村は強風注意報なのに区域では暴風警報と出ていた）。

### 3-9. ブラウズのお気に入り

お気に入りは `Config.browser.favorites` に入り、`ConfigStore.updateFavorites()` だけが書き換える
（重複・件数 30・名前 80 文字の上限が必ず効く）。`PublicConfig` には載せない（どこを見ているかは生活の様子が出るため）。

| 操作 | 場所 |
|---|---|
| ☆／★（登録・解除） | ツールバー。登録時は名前を聞く（題名かホスト名を全選択して出す） |
| ☰ → お気に入り | 一覧。名前を押すと移動、右端の **⋮** から「名前を変更」「削除」 |

- ページ内で履歴だけ書き換えるサイト（YouTube など）は `onPageStarted` を通らないので、`doUpdateVisitedHistory` でも URL を拾う
- 題名は `onPageFinished` の時点ではまだ入っていないことがあるので、`WebChromeClient.onReceivedTitle` を主に使う
- WebView が使えない端末では、落とさずに案内を出す

### 3-10. LAN 公開

`SettingsController.setLanEnabled()` → 設定を保存 → `DashboardServer.restartLater()`（300ms 後に `0.0.0.0` で張り直す）。
PIN が無ければ有効にできない。有効中は `lanSettingsUrl()`（Wi-Fi の IP から組み立てる）を設定画面に出す。

- loopback（タブレット自身・USB の PC）は認証不要。LAN の端末は PIN でログインしたセッションが必要
- `/api/*` はすべて認証が必要。ログインしていない LAN の端末にはログイン画面しか返さない
- XForwardedHeaders は入れない（`X-Forwarded-For` で loopback を偽装されないため）

以前の LAN 公開ボタンが効かなかったのは、タブレットの歯車の設定画面が WebView の中にあり、
その WebView が `confirm()` のダイアログを出せず（`WebChromeClient` が無いとキャンセル扱いになる）、
処理がそこで止まっていたため。いまは確認をアプリのダイアログで出す。

### 3-11. ポート 8080 は固定

変えると `adb forward`・ブラウザの URL・**Spotify の Redirect URI** が同時に壊れる。
Spotify は平文 HTTP の折り返しを 127.0.0.1 にしか認めないので、連携はタブレット本体か USB の PC から行う。

### 3-12. Android の版ごとの違い

| 版 | 違い | 対応している所 |
|---|---|---|
| 7.0（API 24） | Let's Encrypt のルート（ISRG Root X1/X2）を持たない。天気の Open-Meteo が該当 | `network_security_config.xml` で同梱のルートも信頼（`res/raw/isrg_root_x*.pem`） |
| 7.x（API 24/25） | PBKDF2WithHmacSHA256 が無い | `Auth` が SHA1 に落とす |
| 8.0（API 26） | 裏からのサービス起動の制限、通知チャネル | `startForegroundService()` → 5 秒以内に `startForeground()` |
| 9（API 28） | 平文 HTTP が既定で禁止 | 127.0.0.1 と強震モニタのホストだけ許可 |
| 10（API 29） | 裏から Activity を前面に出せない。SSID に位置情報の権限と位置情報サービスが要る | ホームアプリ登録、Wi-Fi カードの案内 |
| 12（API 31） | `WifiInfo` は `NetworkCapabilities.transportInfo` から。裏からの前面サービス開始の制限（起動時・更新時は例外） | `WifiMonitor`、`BootReceiver` |
| 12・12L（API 31・32） | 精密な位置情報（FINE）だけを求めると要求ごと無視される。COARSE も一緒に求める必要がある（SSID が出なくなっていた） | マニフェストと `MainActivity.requestNeededPermissions()` |
| 13（API 33） | 通知の実行時権限。SSID は「付近のデバイス」権限。テーマアイコン（単色） | `MainActivity.requestNeededPermissions()`、`ic_launcher_monochrome.xml` |
| 14（API 34） | 前面サービスの種別が必須 | `specialUse`（マニフェストに用途の説明も書く） |
| 15（API 35、いまの targetSdk） | 全画面表示の強制。起動時に始められない前面サービスの種別（`dataSync` など。`specialUse` は対象外） | システムバーを隠し、切り欠きの分だけ余白を取る |
| 15 以降 | 16KB ページサイズの端末 | 同梱のネイティブライブラリ（Compose の `libandroidx.graphics.path.so`）は 16KB 整列済み |
| 16（API 36） | targetSdk を 36 にすると、大画面での向き・サイズ固定の指定が無視される | いまは targetSdk 35。上げるときに確かめる |

メーカー独自の省電力（自動起動の管理など）は OS の版と関係なく常駐を止め得る。設定画面の
「電池の最適化から除外する」と、README の「メーカー独自の省電力制御」で扱う。

---

## 4. 設定画面の構造

左にメニュー、右に選んだ項目の設定だけを出す。ダッシュボードのカードは 1 枚 = 1 項目で、
そのカードに効く設定を同じ面に置く。メニュー項目の左の点は、いまそのカードを表示しているかを表す。

```
全体   … 配色 / 画面の明るさ / 場所 / 通知
カード … 時刻 天気 防災 LINE メモ 時間別予報 Spotify
         Wi-Fi 端末状態 ニュース 週間予報 タイマー 今日の単語 ハムスター
端末   … ホームアプリ（電池の最適化を含む）/ ネットワーク（LAN 公開）
```

**「表示する」と「取得する」は別物**（防災・ニュース・LINE メモ・Spotify）:
「表示する」はカードを出すかどうか、「取得する」は通信するかどうか。

| Web の API | 扱うもの |
|---|---|
| `POST /api/settings` | 「全て保存」（`SaveAllRequest`: 公開設定・LINE メモ・Spotify） |
| `POST /api/lan` | PIN と LAN 公開（PIN は PBKDF2 + ソルト、平文は保持しない） |
| `POST /api/device` | ホームアプリ登録 |
| `POST /api/sound/preview` | 通知音の試聴（タブレットから鳴る） |
| `GET /api/spotify/start` / `POST /api/spotify/disconnect` | Spotify の連携・解除 |
| `POST /api/refresh` | 全データの取り直し |
