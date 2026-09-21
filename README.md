# Walldash

使っていない Android タブレットを、Wi-Fi の状態・時刻・天気を常時表示する壁掛けダッシュボードにするアプリ。

- ダッシュボード UI は WebView + アプリ内蔵の Ktor サーバー（`assets/web` の HTML/CSS/JS）
- 設定は PC のブラウザから行う（USB 経由が主導線、LAN 公開は明示的なオプトイン）
- 天気は [Open-Meteo](https://open-meteo.com/)（API キー不要）

---

## 必要なもの

| 用途 | 導入 |
|---|---|
| JDK 17+ | 既存の JDK でよい（確認: `java -version`） |
| Android SDK / adb | `brew install --cask android-commandlinetools android-platform-tools` |
| SDK 本体 | `sdkmanager --install "platforms;android-36" "build-tools;36.0.0"` + `sdkmanager --licenses` |
| Gradle | 不要（`./gradlew` が自動取得する。Wrapper は Gradle 8.14.5 で固定） |

`local.properties` の `sdk.dir` が SDK の場所を指していること。

---

## タブレット側の初回準備

1. 設定 → デバイス情報 → **ビルド番号を 7 回タップ**して開発者オプションを有効化
2. 開発者オプション → **USB デバッグを ON**
3. PC と USB 接続 → 端末に出る「USB デバッグを許可しますか」で**許可**
4. `adb devices` に端末が表示されれば準備完了

---

## ビルドとインストール

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 設定画面を開く（USB 経由 — 主導線）

```bash
adb forward tcp:8080 tcp:8080
```

→ PC のブラウザで <http://localhost:8080/settings>

loopback からのアクセスは認証不要。これが設定の標準経路。

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
- LAN からの未認証 `/api/state` は **SSID・IP アドレス・正確な緯度経度をマスク**する
  （返すのは時刻・天気概況・都市名のみ）

> **適用範囲**: LAN 公開は**家庭内の信頼できる LAN 限定**の機能です。通信は平文 HTTP のため、
> ログイン PIN やセッションは同一ネットワーク上で盗聴され得ます。
> ゲスト Wi-Fi、社内共有 LAN、不特定多数が接続するネットワークでは有効にせず、
> USB 経由の設定のみを使ってください。

待受ポートは **8080 固定**。設定項目にしていないのは、変更すると WebView の参照先・`adb forward`・
ブラウザの URL が同時に壊れるため。

---

## 常時運用のための端末設定

### スリープを無効化

```bash
adb shell settings put system screen_off_timeout 2147483647
```

### 再起動後に画面を自動で戻す

`BOOT_COMPLETED` でサービスは自動起動するが、**Android 10 以降のバックグラウンド Activity 起動制限により、
ダッシュボード画面が自動で前面に出るとは限らない**。

対策として設定画面の「端末」セクションから **ホームアプリとして登録**できる。
有効化したあと端末側でホームアプリの選択ダイアログが出たら Walldash を選ぶ。
登録しない場合は、再起動のたびに手動でアプリを開く運用になる。

### メーカー独自の省電力制御

端末のバッテリー設定が「制限」になっていると、**サービスの常駐も `BOOT_COMPLETED` の受信も阻害され得る**。
以下を端末の設定から手動で許可すること（項目名はメーカーによって異なる）。

| メーカー | 確認する項目 |
|---|---|
| Xiaomi / Redmi | 自動起動、バッテリーセーバーの「制限なし」 |
| Samsung | バッテリー → バックグラウンド使用制限 → 「制限しないアプリ」に追加 |
| Huawei | アプリ起動管理 → 手動管理（自動起動・他アプリからの起動・バックグラウンド動作すべて ON） |
| Lenovo / その他 | バッテリー最適化から除外 |

`tools/probe-device.sh` を実行すると、メーカーや省電力の状態を含む端末情報をまとめて採取できる。

---

## UI の開発（端末に入れ直さずに進める）

```bash
node tools/mock-server.mjs
```

→ <http://localhost:8080> で `assets/web` をそのまま配信する。
`/api/*` は Android 側と同じ形の JSON を返し、Wi-Fi の電波強度はゆらぎ、天気は実 Open-Meteo を叩く
（オフライン時は合成データにフォールバック）。ここで UI を完成させてから APK に入れる。

---

## リリース APK

`./gradlew assembleRelease` だけでは署名されない。先に keystore を作る。

```bash
keytool -genkeypair -v -keystore walldash.jks -alias walldash \
  -keyalg RSA -keysize 4096 -validity 10000
```

プロジェクト直下に `keystore.properties` を作る（`.gitignore` 済み）:

```properties
storeFile=walldash.jks
storePassword=***
keyAlias=walldash
keyPassword=***
```

```bash
./gradlew :app:assembleRelease
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

> **keystore は必ずバックアップすること。** 紛失すると同じ署名で更新できなくなり、
> 端末から一度アンインストールしないと入れ替えられなくなる。
> `walldash.jks` とパスワードを別媒体に保管する。

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

## LINE メモ

LINE Bot に送ったメッセージが壁に並ぶ。中継の作り方は `worker/README.md` を参照。

- 新しい順に最大 30 件まで保存され、カードに入りきらない分は指でスクロールして読む
- LINE で **`/clear`** と送ると保存されているメモを全部消す
- 中継（Cloudflare Worker）を更新しないと複数表示と `/clear` は効かない:
  `cd worker && npx wrangler deploy`

## クレジット

### データ提供元

| 用途 | 提供元 | ライセンス / 条件 |
|---|---|---|
| 天気・時間別予報・週間予報 | [Open-Meteo.com](https://open-meteo.com/) | CC BY 4.0 |
| 警報・注意報・地震・津波・台風・噴火 | [気象庁](https://www.jma.go.jp/) | 出典の明示により利用可 |
| 強震モニタ（いま揺れている観測点） | [防災科学技術研究所](https://www.bosai.go.jp/) | 参考表示として利用 |
| Wi-Fi カードの地球儀の海岸線 | [Natural Earth](https://www.naturalearthdata.com/)（1:110m coastline） | パブリックドメイン |

### 回し車のハムスター

画面下で回し車を走るハムスターの意匠は、[Uiverse.io](https://uiverse.io/) の
**Nawsome** 作「Loader」によります（MIT License）。
本プロジェクトでは、走る・休む・外を歩く・立ち止まるの状態遷移を
`app/src/main/assets/web/js/hamster.js` として追加し、見た目の CSS は
`app/src/main/assets/web/css/dashboard.css` の `#hamster` 配下に取り込んでいます。

以下は MIT License の全文です。

```
MIT License

Copyright - 2026 Nawsome 

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
