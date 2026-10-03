# Dashboard

使っていない Android タブレットを、壁に掛けて情報を表示し続けるダッシュボードにするアプリです。

---

# プレビュー

ホーム画面

<img width="640" height="400" alt="Image" src="https://github.com/user-attachments/assets/7ce8f7a1-08c8-45f4-b693-8bc8f2faa51b" />

Spotify全画面

<img width="640" height="400" alt="Image" src="https://github.com/user-attachments/assets/a8f1f9fb-2f54-427c-8644-fe4f85ba01a1" />

カード設定画面

<img width="640" height="400" alt="Image" src="https://github.com/user-attachments/assets/5172c8b6-a417-4a5e-8694-b3fe54c6531a" />

ブラウザ画面

<img width="640" height="400" alt="Image" src="https://github.com/user-attachments/assets/bc79331b-dad4-4607-8ceb-25a52fa779f1" />

---

**表示できるもの**: 時刻・天気（時間別予報・週間予報）・防災情報（警報・地震・津波・台風・噴火）・
ニュース（RSS）・Wi-Fi と端末の状態・タイマー・今日の単語・LINE メモ・Spotify で再生中の曲

**追加のカード**（既定は非表示。設定画面の各カードで表示に切り替えます）: アナログ時計・予定表（iCloud カレンダー）・
電車の運行情報・雨雲レーダー・日の出／日の入りと月の満ち欠け・カウントダウン・今日は何の日・株価・暗号通貨・計算機・写真（iCloud の共有アルバム）

**見た目**: ダーク／ホワイトのテーマ、アクセント色（14 色）、好きな画像を背景にしてカードを透かす表示
（設定画面の「全体 → テーマ」）。カードは画面に収まる数まで表示できます。カードが多いときは週間予報を半分の幅まで縮め（横にスクロールして見られます）、空いた場所にカードを並べます。<br>
カードの幅と置き場所は設定画面の「全体 → カードの配置」で変えられます。カードの右の壁を左右にドラッグすると幅が、下の壁を上下にドラッグすると高さ（下の行に空きがあるときだけ）が、カードを長押ししてから動かすと（Web の設定画面では掴んで動かすと）ほかの行や同じ行の別の位置へ入れ替わります（4 行固定。行の右端や行そのものを空けたままでも保存できます）。<br>
カードを枠の外まで動かして離すとダッシュボードから外れ、枠の下の「使っていないカード」から枠の中へ動かすと足せます。<br>

時刻カードの見出しの右のボタンを押すと、時刻と日付を画面いっぱいに大きく出します（左上の「<」で戻る。右上の「暗くしない」は Spotify の全画面と共通）。<br>
雨雲レーダーは、ドラッグで地図を動かし、右上の「＋」「−」で拡大・縮小、その下のボタンでズームはそのままで現在地（「場所」の地点）へ戻れます。<br>
見出しの右のボタンで画面いっぱいに出せます（ボタンが大きく、画面の端まで地図が埋まります）。<br>
暗号通貨カードは、選んだ 1 つの通貨の値とチャート（折れ線かろうそく足）を出し、見出しの右の「−」「＋」でチャートの期間（24 時間・7 日・30 日・1 年）を変えられます。<br>
見出しのすぐ右のボタンで全画面にすると、通貨（主な 13 種）・期間（90 日も）・描き方・円／米ドルを切り替えて、値の目盛りと時刻の入った大きなチャートと 24 時間の出来高を見られます（チャートに触れている間はその時刻の値を出します）。<br>
全画面（時刻・Spotify・雨雲レーダー・暗号通貨）の右上の「暗くしない」は共通です。<br>
カードの背景色は「テーマ」で全カードまとめて変えられます（背景画像があるときの不透明度の設定はそのまま効きます）。<br>
防災カードの台風の名前を押すと、台風の進路図（これまでの経路・強風域・暴風域・予報円・暴風警戒域と各予報の時刻、右に時刻ごとの位置・気圧・風速）を画面いっぱいに出します（左上の「<」で戻る）。<br>
カードごとに、中身が崩れない最小の幅があります。カードを表示するとき空きが足りなければほかのカードを最小の幅まで縮めて入れ、それでも収まらなくなるときは設定画面が理由を出して止めます。<br>

| 入れればすぐ使える | 自分のアカウントの用意が必要 |
|---|---|
| 時刻、天気、防災、ニュース、Wi-Fi、端末状態、タイマー、今日の単語、アナログ時計、雨雲レーダー、日の出・月、カウントダウン、今日は何の日、株価、暗号通貨、計算機 | LINE メモ（LINE と Cloudflare）、Spotify、予定表（iCloud）、運行情報（ODPT の登録）、写真（iCloud の共有アルバム） |

アプリストアでは配布していません。入れ方は 2 通りあります。

| 入れ方 | 向いている場合 |
|---|---|
| [APK でインストール](#apk-でインストール) | できあがったアプリのファイル（APK）を入れるだけ。PC にビルド環境を入れる必要はありません。**ふつうはこちら** |
| [ソースコードからインストール](#ソースコードからインストール) | PC でこのソースコードからアプリを作って（ビルドして）入れる。コードを変えて使いたい場合 |

---

## APK でインストール

APK は、このリポジトリの GitHub のページの **[Releases](../../releases)**（ページ右側の「Releases」）に置いています。
いちばん新しいリリースの「Assets」にある **`dashboard.apk`** を使います。
Android 7.0 以上のタブレットに入ります。

> リポジトリの中（ソースコードの一覧や ZIP）には APK は入っていません。
> ビルドのたびに変わる 20 MB ほどのファイルなので Git では管理せず、Releases にだけ置いています。

**A と B のどちらか一方**を行います。

### A. タブレットだけで入れる

1. タブレットのブラウザ（Chrome など）で Releases のページを開き、`dashboard.apk` をダウンロードする
2. ダウンロードの通知、または「Files」（ファイル）アプリの「ダウンロード」から `dashboard.apk` を開く
3. 「セキュリティ上の理由から、この提供元からの不明なアプリをインストールすることはできません」のように出たら、
   「設定」を押して「**この提供元のアプリを許可**」を ON にし、戻る
   （Android 7 では「設定」→「セキュリティ」→「**提供元不明のアプリ**」を ON にします）
4. 「インストール」を押す。Play プロテクトの確認が出たら「インストールする」を選ぶ
5. 「開く」を押すか、アプリ一覧から **Dashboard** を開く

PC でダウンロードした `dashboard.apk` を、Google ドライブやメール、USB メモリなどでタブレットに送って開いても同じです。

### B. PC から USB ケーブルで入れる

PC に `adb` が必要です（Mac は `brew install --cask android-platform-tools`、
Windows / Linux は [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) を展開して使います）。

1. 下の「[3. タブレットの USB デバッグを ON にする](#3-タブレットの-usb-デバッグを-on-にする)」を行う
2. PC で `dashboard.apk` をダウンロードし、そのフォルダでターミナルを開いて実行する

```bash
adb install -r dashboard.apk
```

`Success` と出たら完了です。タブレットのアプリ一覧から **Dashboard** を開きます。

### 入れたあと

「[5. タブレットで初期設定をする](#5-タブレットで初期設定をする)」へ進みます。

更新するときは、新しい `dashboard.apk` を同じ手順で入れ直します（上書きされ、**設定はそのまま残ります**）。

> Releases の APK と、自分の PC でビルドしたアプリは署名が違うので、互いに上書きできません
> （「アプリがインストールされていません」、`adb` では `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
> 切り替えるときは一度アンインストールしてから入れ直します。**このとき設定は消えます**。

---

## ソースコードからインストール

PC でこのソースコードからアプリを作り（ビルド）、USB ケーブルでタブレットに入れます。
Android アプリを作った経験がなくても、下の手順どおりに進めれば入れられます。

全体の流れ:

1. 用意するものをそろえる
2. PC にビルド環境を入れる
3. タブレットの USB デバッグを ON にする
4. アプリを作ってタブレットに入れる
5. タブレットで初期設定をする

### 1. 用意するもの

| もの | 条件 |
|---|---|
| Android タブレット | Android 7.0 以上。動作を確認しているのは Lenovo TB-X306F（Android 10）のみ。画面の横幅が狭い端末（スマホなど）ではカードを 2 列にして縦にスクロールします |
| PC | Windows / Mac / Linux のどれでも |
| USB ケーブル | **データ通信に対応したもの**。充電専用のケーブルだと PC がタブレットを認識しません |
| Wi-Fi | タブレットがインターネットにつながること（天気やニュースの取得に使います） |
| このリポジトリ | GitHub のページの緑の「Code」ボタン →「Download ZIP」で取得して展開するか、`git clone` します |

### 2. PC にビルド環境を入れる

**A と B のどちらか一方**を行います。迷ったら A にしてください。

#### A. Android Studio を使う（Windows / Mac / Linux）

1. <https://developer.android.com/studio> から Android Studio をダウンロードしてインストールします
2. 初回起動時のセットアップ画面では「Standard」を選び、表示されるライセンスにすべて同意して最後まで進めます

Java や Android SDK は Android Studio が自動で入れるので、ほかに入れるものはありません。

#### B. ターミナルを使う（Mac）

[Homebrew](https://brew.sh/) が入っている前提です。Java と Android SDK を入れます:

```bash
brew install --cask temurin@17 android-commandlinetools android-platform-tools
```

Android SDK のライセンスに同意します（何度か `y/N` と聞かれるので、すべて `y` と入力して Enter）:

```bash
sdkmanager --licenses
```

> Java（JDK）は **17 か 21** を使ってください。このプロジェクトが使う Gradle 8.14 は JDK 25 以降に対応していません。
> 入っている版は `java -version` で確認できます。

### 3. タブレットの USB デバッグを ON にする

アプリストア以外からアプリを入れるための設定です。項目の名前は機種によって少し違います。

1. タブレットの「設定」→「タブレット情報」（「デバイス情報」「端末情報」などの場合もあります）を開く
2. 「ビルド番号」を **7 回続けてタップ**する。「これでデベロッパーになりました」と出れば OK
   （見当たらない場合は「ソフトウェア情報」の中にあることが多いです）
3. 「設定」→「システム」→「開発者向けオプション」を開き、「**USB デバッグ**」を ON にする
4. タブレットを USB ケーブルで PC につなぐ
5. タブレットに「USB デバッグを許可しますか？」と出たら、「このパソコンからのアクセスを常に許可する」に
   チェックを入れて「**許可**」を押す（出ない場合はケーブルを抜き差しします）

### 4. アプリを作ってタブレットに入れる

手順 2 で選んだほうを行います。

#### A. Android Studio の場合

1. Android Studio の「Open」で、このリポジトリのフォルダ（`settings.gradle.kts` が入っているフォルダ）を開く。
   「Trust Project?」と聞かれたら「Trust Project」を選ぶ
2. 画面下のステータスバーで同期（Gradle Sync）が終わるまで待つ。初回は必要なファイルのダウンロードで
   数分〜十数分かかります
3. 画面上部の実行先の欄にタブレットの名前が出ていることを確認する
4. 緑の ▶（Run 'app'）を押す。タブレットにアプリが入り、そのまま起動します

> 「Android Gradle Plugin をアップグレードしますか」という案内が出ても、アップグレードせずに閉じてください。
> 動作を確認している版から変わるため、ビルドが通らなくなることがあります。

#### B. ターミナルの場合

ターミナルでこのリポジトリのフォルダに移動します（`cd ` と入力したあと、Finder からフォルダを
ドラッグ＆ドロップすると場所が入ります）。

Android SDK の場所をこのプロジェクトに伝えます。**最初の 1 回だけ**必要です:

```bash
echo "sdk.dir=$(brew --prefix)/share/android-commandlinetools" > local.properties
```

> これを飛ばすとビルドが `SDK location not found` で失敗します。
> `local.properties` は PC ごとに違う設定なので、リポジトリには含めていません。

タブレットが認識されているか確認します。一覧に `device` と出れば OK です
（`unauthorized` と出た場合は、タブレットの画面で「許可」を押します）:

```bash
adb devices
```

アプリを作ってタブレットに入れます。初回は必要なファイルのダウンロードで数分〜十数分かかります:

```bash
./gradlew :app:installDebug
```

`BUILD SUCCESSFUL` と出たら完了です。タブレットのアプリ一覧から **Dashboard** を開きます。

### 5. タブレットで初期設定をする

初めて開くと、権限の確認が出ます。**どちらも「許可」**を選んでください。

| 出てくる確認 | 使いみち |
|---|---|
| 通知（Android 13 以降） | アプリが動き続けていることを示す通知を出すため |
| 付近のデバイス（Android 13 以降）／位置情報（Android 12 以前） | Wi-Fi カードにネットワーク名（SSID）を出すため。Android はこの権限がないとネットワーク名を教えてくれません。**タブレットの現在地を取得することはありません**（天気の地点は設定画面で自分で選びます） |

次に、画面下の**歯車ボタン**で設定画面を開き、最低限この 3 つを設定します。変更したら、左側のメニューの下にある **「全て保存」** を押します（保存しないまま閉じようとすると確認が出ます）。

| 設定画面の項目 | 設定すること |
|---|---|
| 全体 → 場所 | 住んでいる地域を**ローマ字か英語**で検索して選ぶ（例: `Sapporo`）。**設定しないと東京の天気が出ます**。防災の警報・注意報も、ここで選んだ地点の市町村のものになります |
| カード → 防災 | 「防災情報を取得する」を ON にする（地域を選ぶ必要はありません） |
| カード → ニュース | 読みたいニュースの RSS の URL を登録する |

壁に掛けて使い続ける場合は、下の「[常時運用のための端末設定](#常時運用のための端末設定)」も行ってください。
LINE メモと Spotify は、使う場合だけ設定します（[LINE メモ](#line-メモ)・[Spotify](#spotify)）。

### PC のブラウザから設定する（任意）

タブレットの画面で文字を打つのが面倒なときは、USB でつないだまま PC のブラウザから同じ設定画面を開けます。

```bash
adb forward tcp:8080 tcp:8080
```

PC のブラウザで <http://localhost:8080/settings> を開きます。USB でつながった PC からしか届かない経路なので、
ログインは不要です。接続がよく切れる場合は、`bash tools/keep-forward.sh` を動かしておくと自動でつなぎ直します。

> Android Studio で環境を入れた場合、`adb` は次の場所にあります（そのままでは `adb` と打っても見つかりません）。
> Mac: `~/Library/Android/sdk/platform-tools/adb`　Windows: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`

### アプリを更新する

APK で入れた場合は、「[APK でインストール](#apk-でインストール)」の手順で新しい APK を入れ直します。

ソースコードから入れた場合は、新しいソースコードを取得して（`git pull`、または ZIP を取り直して展開）、手順 4 をもう一度行います。
**設定はそのまま残ります**。ZIP を別のフォルダに展開し直した場合は、手順 4-B の `local.properties` も作り直してください。

> 別の PC で作ったアプリや Releases の APK で上書きしようとすると、`INSTALL_FAILED_UPDATE_INCOMPATIBLE` で失敗します
> （開発用の署名が PC ごとに違うため）。その場合は一度アンインストールしてから入れ直します。**このとき設定は消えます**。

### アンインストール

タブレットの「設定」→「アプリ」→「Dashboard」→「アンインストール」。

### うまくいかないとき

| 症状 | 対処 |
|---|---|
| `SDK location not found` | 手順 4-B の `local.properties` を作る（Android Studio では自動で作られます） |
| `adb devices` に何も出ない | データ通信対応のケーブルか、USB デバッグが ON かを確認する。別の USB ポートも試す。Windows ではメーカーの USB ドライバが必要な機種があります |
| `adb devices` に `unauthorized` と出る | タブレットの画面に出ている「USB デバッグを許可」で「許可」を押す。出ていなければケーブルを抜き差しする |
| Java（JDK）に関するエラーでビルドが止まる | JDK 17 か 21 を使う。Mac で複数の JDK が入っている場合は `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` を実行してからビルドし直す |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`、「アプリがインストールされていません」 | 上の「アプリを更新する」を参照 |
| APK を開いても「インストール」が出ない | 「[APK でインストール](#apk-でインストール)」の A-3 の「この提供元のアプリを許可」を ON にする |
| Wi-Fi カードに「権限が必要」と出る | タブレットの「設定」→「アプリ」→「Dashboard」→「権限」で「付近のデバイス」または「位置情報」を許可する |
| Wi-Fi カードに「位置情報サービスを ON に」と出る | タブレットの位置情報を ON にする（Android の仕様で、ネットワーク名の取得に必要です） |
| ブラウズが開かない | Play ストアで「Android System WebView」を有効にして最新にする（ブラウズだけが WebView を使います） |
| 再起動したり、しばらく置いたりするとアプリが止まっている | 設定画面の「端末 → ホームアプリ」で「電池の最適化から除外する」を押す。下の「[メーカー独自の省電力制御](#メーカー独自の省電力制御)」も確認する |

---

## 仕組み

- タブレットの画面（カード・設定・ブラウズ）は **Jetpack Compose** で描くネイティブアプリ（`app/src/main/kotlin/app/dashboard/ui/`）
- データは常駐サービス（`DashboardService`）が各取得先から定期的に集め、画面は同じプロセスの中からそれを読む
- PC や他の端末のブラウザから開く **Web の設定画面**（`assets/web`）は、アプリ内蔵の Ktor サーバーが配信する。
  既定では USB でつないだ PC からだけ開け、LAN の他の端末から開くには「LAN 公開」を有効にする
- 天気は [Open-Meteo](https://open-meteo.com/)（API キー不要）。Android 7.0 でも繋がるよう、Let's Encrypt のルート証明書を同梱している
- コード同士の関係の詳しい説明は [docs/architecture.md](docs/architecture.md)
- Gradle は不要（`./gradlew` が自動取得する。Wrapper は Gradle 8.14.5 で固定）。
  足りない SDK の部品（Platform 36・Build-Tools 35 など）も、ライセンスに同意済みならビルド時に自動で入る

---

## セキュリティ: LAN 公開について

**既定では `127.0.0.1` のみで待ち受ける。** 他の端末からは一切アクセスできない。

設定画面で「LAN 公開」を有効にすると `0.0.0.0` で待ち受け、同一ネットワークの他端末から
`http://<タブレットのIP>:8080/settings` を開けるようになる。有効化には PIN（6 桁以上）の設定が必須。

実装している防御:

- PIN は PBKDF2 + ソルトでハッシュ化して保存（平文は保持しない）
- ログイン試行は IP 単位で 5 回失敗すると 5 分ロック
- セッションは 24 時間で失効。PIN 変更時に全セッションを無効化
- Cookie は `HttpOnly` + `SameSite=Strict`、全 POST で `Origin` と `Host` の一致を検証
- `X-Forwarded-For` による loopback 偽装を防ぐため、Ktor の XForwardedHeaders は意図的に使わない
- ログインしていない LAN の端末には、ログイン画面以外を返さない（`/api/*` はすべて認証が必要）

> **適用範囲**: LAN 公開は**家庭内の信頼できる LAN 限定**の機能です。通信は平文 HTTP のため、
> ログイン PIN やセッションは同一ネットワーク上で盗聴され得ます。
> ゲスト Wi-Fi、社内共有 LAN、不特定多数が接続するネットワークでは有効にせず、
> USB 経由の設定のみを使ってください。

待受ポートは **8080 固定**。設定項目にしていないのは、変更すると `adb forward`・ブラウザの URL・
Spotify の Redirect URI が同時に壊れるため。

---

## 常時運用のための端末設定

### スリープを無効化

ダッシュボードを表示している間は、アプリが画面を消さないようにしている。
ダッシュボード以外の画面になっても消えないようにするには、端末のスリープ自体を無効化する
（端末の「設定 → ディスプレイ → スリープ」を最長にするのでもよい）:

```bash
adb shell settings put system screen_off_timeout 2147483647
```

### 再起動後に画面を自動で戻す

`BOOT_COMPLETED` でサービスは自動起動するが、**Android 10 以降のバックグラウンド Activity 起動制限により、
ダッシュボード画面が自動で前面に出るとは限らない**。

対策として設定画面の「端末 → ホームアプリ」から **ホームアプリとして登録**できる。
有効化したあと端末側でホームアプリの選択ダイアログが出たら Dashboard を選ぶ。
登録しない場合は、再起動のたびに手動でアプリを開く運用になる。

### メーカー独自の省電力制御

端末のバッテリー設定が「制限」になっていると、**サービスの常駐も `BOOT_COMPLETED` の受信も阻害され得る**。
まず設定画面の「端末 → ホームアプリ」にある **「電池の最適化から除外する」** を押す。
そのうえで、以下を端末の設定から手動で許可すること（項目名はメーカーによって異なる）。

| メーカー | 確認する項目 |
|---|---|
| Xiaomi / Redmi | 自動起動、バッテリーセーバーの「制限なし」 |
| Samsung | バッテリー → バックグラウンド使用制限 → 「制限しないアプリ」に追加 |
| Huawei | アプリ起動管理 → 手動管理（自動起動・他アプリからの起動・バックグラウンド動作すべて ON） |
| Lenovo / その他 | バッテリー最適化から除外 |

`tools/probe-device.sh` を実行すると、メーカーや省電力の状態を含む端末情報をまとめて採取できる。

---

## UI の開発

タブレットの画面（Compose）は、実機に入れて確かめる（手順 4）。Android Studio なら ▶ を押すたびに入れ直せる。

Web の設定画面だけは、タブレットなしで PC のブラウザで作れる:

```bash
node tools/mock-server.mjs
```

→ <http://localhost:8080/settings>。`/api/*` は実機と同じ形の JSON を返す（保存しても実機には何も起きない）。

---

## ビルドした APK の置き場所

デバッグ版をビルドするたびに（`./gradlew assembleDebug`、Android Studio の「Build → Build APK(s)」）、
リポジトリの直下に **`dashboard.apk`** ができる（`app/build/outputs/apk/debug/app-debug.apk` の写し）。
このファイルをタブレットに送って開くか、`adb install -r dashboard.apk` で入れられる
（手順は「[APK でインストール](#apk-でインストール)」と同じ）。
`dashboard.apk` は `.gitignore` 済み（ビルドのたびに変わる 20 MB ほどのファイルのため）。
リモートのリポジトリには入らないので、配るときは GitHub の Releases に添付する。

## リリース APK

`./gradlew assembleRelease` だけでは署名されない。先に keystore を作る。

```bash
keytool -genkeypair -v -keystore dashboard.jks -alias dashboard \
  -keyalg RSA -keysize 4096 -validity 10000
```

プロジェクト直下に `keystore.properties` を作る（`.gitignore` 済み）:

```properties
storeFile=dashboard.jks
storePassword=***
keyAlias=dashboard
keyPassword=***
```

```bash
./gradlew :app:assembleRelease
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

> **keystore は必ずバックアップすること。** 紛失すると同じ署名で更新できなくなり、
> 端末から一度アンインストールしないと入れ替えられなくなる。
> `dashboard.jks` とパスワードを別媒体に保管する。

設定には LAN 公開用 PIN のハッシュが含まれるため、`allowBackup="false"` とし、
`data_extraction_rules.xml` でクラウドバックアップ・端末間転送の両方から除外している。

---

## SDK バージョン方針

- `compileSdk = 36`（Android 16）: 新しい API を参照できるようにする
- `targetSdk = 35`: Android 16 の挙動変更を初期構築時に踏まないため。
  実機での常駐・再起動・省電力の検証を通過したあと 36 への引き上げを検討し、
  問題が出て 35 に戻す場合はその理由をここに記録する
- `minSdk = 24`

---

## 通知

防災情報・充電ケーブルの抜き差し・タイマーのほかに、次のときも音とバナーで知らせる。
設定画面の「通知」で、それぞれ知らせる・知らせないと音を選べる（切ると音もバナーも出ない）。

| 知らせるとき | 決められる値 | 既定 |
|---|---|---|
| 電池の残量が少ない（充電していないとき） | 何 % を切ったら | 20% |
| 電池の温度が高い | 何 ℃ を超えたら | 40 ℃ |
| LINE メモに新しいメモが届いた（中身をバナーに出す） | — | — |
| Wi-Fi の接続が切れた（4 秒ほど続けて切れていたら） | — | — |
| まもなく雨が降り始める | 何分以内に降り始めるとき | 30 分 |

雨は、気象庁の降水ナウキャスト（雨雲レーダーの 5 分ごと・60 分先までの予報）で「場所」の地点の画素を見て判断する。
国外の地点などナウキャストが読めないときは、時間別予報の天気と降水確率で代わりに判断する。
バナーは全画面を開いているときも上に出る。

---

## LINE メモ

LINE Bot に送ったメッセージが壁に並ぶ。中継の作り方は [`worker/README.md`](worker/README.md) を参照。

- 新しい順に最大 30 件まで保存され、カードに入りきらない分は指でスクロールして読む
- LINE で **`/clear`** と送ると保存されているメモを全部消す
- 中継（Cloudflare Worker）を更新しないと複数表示と `/clear` は効かない:
  `cd worker && npx wrangler deploy`

## Spotify

Spotify で再生中の曲のジャケットと曲名を出し、再生・一時停止・曲送りができる。
再生中は見出しの右に小さなボタンが出て、押すと再生中の曲を画面いっぱいに出す（ジャケットの色の背景、下の真ん中に前の曲・再生／一時停止・次の曲のボタン、右下に再生時間、左上の「<」で閉じる）。
タブレットから音は出ない（操作は Spotify を鳴らしている端末に送られる）。曲送りには Spotify Premium が必要。

再生中はカードの見出しの右のボタンで、曲を画面いっぱいに出せる。操作ボタン・再生時間・右上の「暗くしない」は、
5 秒触らなければ溶けるように消え、画面に触れると戻る。「暗くしない」をオンにすると、全画面の間は無操作でも画面を暗くしない。
全画面を閉じてダッシュボードに戻ると、「暗くしない」がオンのままでも、設定した時間だけ触らなければ暗くなる。

全画面でジャケットを押すと、ジャケットが左へ動いて右に歌詞が出る（もう一度押すと歌詞を消して真ん中へ戻る）。
歌詞は再生位置に合わせて流れ、いま歌っている行が真ん中に白く太く出る。Spotify の Web API は歌詞を返さないので、
有志の歌詞データベース [LRCLIB](https://lrclib.net/)（登録不要）から、曲名・アーティスト名・アルバム名・長さで探す。
LRCLIB に時刻付きの歌詞が無い曲は、曲の長さから目安の時刻を振って流す（位置は目安）。登録の無い曲は出ない。
LRCLIB は混み合うと 503 を返すので、間を空けて試し直し、それでも取得できなければ歌詞を開いている間は自動で探し直す。
LRCLIB に時刻付きの歌詞が無い曲や、LRCLIB が使えないときは、NetEase Cloud Music（網易雲音楽）の公開の API（非公式・登録不要）からも探す。
見つけた歌詞は端末に保存し（300 曲まで）、次からは通信なしで出す。出典は歌詞の欄の右下に出る。

使うには、自分用の Spotify アプリ登録（無料）が要る:

1. [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) で「Create app」を押す
2. **Redirect URI** に `http://127.0.0.1:8080/api/spotify/callback` を追加し、API は「Web API」を選ぶ
3. できた **Client ID** を、設定画面の「カード → Spotify」に入力して「全て保存」し、「Spotify と連携」を押す
   （Client Secret は使わない）

## 予定表（iCloud カレンダー）

iCloud のカレンダーの予定を今日から数日ぶん並べる。つなぎ方は 2 通り（設定画面の「カード → 予定表」）。

| つなぎ方 | 用意するもの |
|---|---|
| Apple ID で接続（すべてのカレンダー） | Apple ID と **App 用パスワード**。[account.apple.com](https://account.apple.com) →「サインインとセキュリティ」→「App 用パスワード」で作る 16 文字（ふだんのパスワードでは接続できない。いつでも取り消せる） |
| 共有カレンダーの公開 URL | iPhone の「カレンダー」→ カレンダーの (i) →「公開カレンダー」を ON にして出る `webcal://` の URL（URL を知っている人は誰でも予定を読めるので扱いに注意） |

## 運行情報（公共交通オープンデータ）

[公共交通オープンデータセンター](https://developer.odpt.org/) で利用者登録（無料）をするともらえるアクセストークンを、
設定画面の「カード → 運行情報」に入れて「全て保存」する。東京メトロ・都営地下鉄・私鉄各社の運行情報が取れる。
JR 東日本などは「公共交通オープンデータチャレンジ」用の API にだけ載っているので、チャレンジに参加登録してもらえる
別のトークンを「チャレンジ」の欄に入れる。トークンを保存したら「路線の一覧を読み込む」で表示する路線を選ぶ
（選ばなければ、遅れや運転見合わせが出ている路線だけを出す）。

運行情報は、登録したトークンの API に配信の無い事業者の路線を「配信なし」と出す。本番の API（`api.odpt.org`）の運行情報は
東京メトロ・都営地下鉄・つくばエクスプレス・東京臨海高速鉄道・多摩モノレール・横浜市営地下鉄だけで（2026-09-28 に確認）、
JR 東日本（常磐線・山手線など）は路線の一覧には載っていても運行情報はチャレンジの API にしか無い。

## 写真（iCloud の共有アルバム）

iCloud の**共有アルバム**の写真を、決めた間隔（10 秒〜1 日）で切り替えて出す。カードを押すと次の写真へ進む。
iPhone の「写真」→ 共有アルバムを開く → 人のアイコン →「公開 Web サイト」を ON にして出る
`https://www.icloud.com/sharedalbum/#…` の URL を、設定画面の「カード → 写真」に入れて「全て保存」する
（URL を知っている人は誰でも写真を見られるので扱いに注意）。
iCloud 写真のライブラリそのものは、Apple ID の 2 ファクタ認証が要る非公開の仕組みのため読めない（App 用パスワードも使えない）。

## 株価

値は Yahoo Finance の公開されていない API から取る。数十分の遅れがあり、予告なく取れなくなることがある。
銘柄は Yahoo Finance の記号で指定する（例: `^N225`・`^DJI`・`7203.T`・`USDJPY=X`）。

## 暗号通貨

選んだ 1 つの暗号通貨の値・変化率・期間の高値と安値と、チャートを 1 つだけ出す。
設定画面の「カード → 暗号通貨」で、通貨（主な 13 種か、CoinGecko の ID を自分で入力）・値の通貨（円／米ドル）・
チャートの期間（24 時間／7 日／30 日／1 年）・描き方（折れ線／ろうそく足）・更新の間隔（1 分〜1 時間）を選ぶ。
期間は、カードの見出しの右の「−」「＋」でも設定を開かずに変えられる。
見出しのすぐ右のボタンで全画面にすると、ほかの通貨・90 日の期間・描き方・円／米ドルをその場で切り替えて見られる（設定は変わらない）。
全画面のチャートは右に値の目盛り、下に時刻があり、触れている間はその時刻の値（ろうそく足なら始値・高値・安値・終値）を出す。下は 24 時間の出来高の推移。

値は [CoinGecko](https://www.coingecko.com/) の公開 API（登録不要）から取る。数分の遅れがあり、混み合うと一時的に取れないことがある
（登録なしの枠は 1 分に数回まで）。Phantom の API は外部のアプリに公開されていないため使っていない。

## 個人に紐づく値の置き場所

このリポジトリは**そのまま公開できる状態を保つ**。地名・県名・アカウント固有の ID など、
持ち主に紐づく値はコードにもドキュメントにも書かず、`private/` にまとめる
（`.gitignore` 済み）。

| 置き場所 | 中身 |
|---|---|
| `private/personal-data.md` | 実際の地点・府県予報区、Cloudflare の KV namespace id、環境依存ファイルの一覧 |
| 端末の `filesDir/config.json` | LINE メモのトークン、Spotify の認可情報、LAN 公開の PIN。**リポジトリには複製しない** |

`docs/shots/` と `docs/device-probe-*.txt` も除外してある。前者は壁に出ている内容
（SSID・IP アドレス・LINE メモの本文・地点名・再生中の曲）がそのまま写るため。

## クレジット

### データ提供元

| 用途 | 提供元 | ライセンス / 条件 |
|---|---|---|
| 天気・時間別予報・週間予報 | [Open-Meteo.com](https://open-meteo.com/) | CC BY 4.0 |
| 警報・注意報・地震・津波・台風・噴火 | [気象庁](https://www.jma.go.jp/) | 出典の明示により利用可 |
| 強震モニタ（いま揺れている観測点） | [防災科学技術研究所](https://www.bosai.go.jp/) | 参考表示として利用 |
| Wi-Fi カードの地球儀の海岸線 | [Natural Earth](https://www.naturalearthdata.com/)（1:110m coastline） | パブリックドメイン |
| 雨雲レーダーの雨雲 | [気象庁](https://www.jma.go.jp/)（降水ナウキャスト） | 出典の明示により利用可 |
| 雨雲レーダーの地図 | [Esri](https://www.esri.com/)（World Light / Dark Gray Base。文字・道路の記号の無い灰色の地図） | 出典の明示（Esri, HERE, Garmin, © OpenStreetMap contributors） |
| 運行情報 | [公共交通オープンデータ協議会](https://www.odpt.org/) | 公共交通オープンデータ基本ライセンス（チャレンジの API はチャレンジ限定ライセンス） |
| 今日は何の日 | [Wikipedia 日本語版](https://ja.wikipedia.org/)（日付の記事） | CC BY-SA 4.0 |
| 株価 | Yahoo Finance（非公式のチャート API） | — |
| 暗号通貨 | [CoinGecko](https://www.coingecko.com/)（公開 API、登録不要） | 出典の明示 |
| 台風の進路図 | [気象庁](https://www.jma.go.jp/)（台風情報）、地図は Esri（雨雲レーダーと同じ） | 出典の明示により利用可 |
| Spotify の全画面の歌詞 | [LRCLIB](https://lrclib.net/)、無ければ [NetEase Cloud Music](https://music.163.com/)（非公式の API） | 利用者が登録した歌詞 |
| 写真 | 利用者の iCloud 共有アルバム | — |
| 祝日 | [内閣府「国民の祝日」](https://www8.cao.go.jp/chosei/shukujitsu/gaiyou.html) | — |
| 日の出・日の入り | Open-Meteo.com | CC BY 4.0 |

### 回し車のハムスター

画面下で回し車を走るハムスターの意匠は、[Uiverse.io](https://uiverse.io/) の
**Nawsome** 作「Loader」によります（MIT License）。
元の CSS の形・色・動きを Jetpack Compose の描画に移し、走る・止まる・休む・走り出す・外を歩く・立ち止まるの状態遷移を加えて
`app/src/main/kotlin/app/dashboard/ui/dashboard/Hamster.kt` に収めています。

以下は MIT License の全文です。

```
MIT License

Copyright - 2026 Nawsome 

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
