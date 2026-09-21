# Walldash — LINE メモ中継 Worker

LINE Bot に送ったメッセージを、壁掛けダッシュボードに表示するための中継サーバーです。

## なぜ中継が必要か

LINE Messaging API は**公開された HTTPS の Webhook URL** にしかメッセージを届けません。
一方タブレットは家庭内 NAT の内側にあり、設計上 `127.0.0.1` しか待ち受けません。
さらに LINE には「Bot 宛のメッセージを後から取得する」API がないため、受け取って保持する場所が必要です。

```
LINE アプリ → LINE Platform → この Worker(/line/webhook) → KV に保存
                                     ↑
                    タブレットが /memo を外向きにポーリング
```

**タブレットはインターネットからの着信を一切受けません。**

## セットアップ

### 1. LINE の Messaging API チャネルを作る

1. [LINE Developers](https://developers.line.biz/console/) にログイン
2. プロバイダーを作成 → **Messaging API** チャネルを新規作成
3. 「Messaging API設定」タブで以下を控える
   - **チャネルシークレット**（Basic settings タブ）
   - **チャネルアクセストークン（長期）**（発行ボタンを押す）
4. 同じタブで「応答メッセージ」を**オフ**、「Webhook」を**オン**にする
5. スマホの LINE で、このチャネルの QR コードから Bot を友だち追加する

### 2. Worker をデプロイする

```bash
cd worker
npm install
npx wrangler login
```

秘密情報を設定します。**値はプロンプトに直接入力してください**（コマンド引数に書くとシェル履歴に残ります）。

```bash
npx wrangler secret put LINE_CHANNEL_SECRET
```

```bash
npx wrangler secret put LINE_CHANNEL_ACCESS_TOKEN
```

端末トークンは自分で生成します。これはタブレットが `/memo` を読むための鍵で、
**設定しないと URL を知る人が誰でもあなたのメモを読めます**。

```bash
openssl rand -hex 32
```

出てきた値を控えて、次のコマンドのプロンプトに貼り付けます。

```bash
npx wrangler secret put DEVICE_TOKEN
```

デプロイします。

```bash
npx wrangler deploy
```

表示される `https://walldash-line-relay.<あなた>.workers.dev` を控えます。

### 3. Webhook URL を LINE に登録する

LINE Developers の「Messaging API設定」→ Webhook URL に次を設定して「検証」を押します。

```
https://walldash-line-relay.<あなた>.workers.dev/line/webhook
```

### 4. 自分の LINE ユーザー ID を許可リストに入れる

Bot に何かメッセージを送ってください。まだ許可されていないので、Bot が**あなたのユーザー ID を返信**します。
その ID を許可リストに登録します（複数人ぶん登録する場合はカンマ区切り）。

```bash
npx wrangler secret put ALLOWED_USER_IDS
```

再度メッセージを送って「ダッシュボードに表示しました。」と返ってくれば成功です。

> 許可リストを設定しないと**誰もメモを登録できません**。これは、Bot の ID を知った第三者が
> あなたの家の壁に文字を出せてしまうのを防ぐためです。

### 5. タブレット側に登録する

USB 接続した状態で:

```bash
adb forward tcp:8080 tcp:8080
```

PC のブラウザで <http://localhost:8080/settings> を開き、「LINE メモ」セクションに入力します。

| 項目 | 値 |
|---|---|
| 中継先の URL | `https://walldash-line-relay.<あなた>.workers.dev/memo` |
| 端末トークン | 手順 2 で生成した `DEVICE_TOKEN` と同じ値 |

## 使い方

- Bot にメッセージを送ると、新しい順に壁へ積まれます。1 件だけのときは大きく、
  複数あるときは一覧になり、入りきらない分はカードを指でスクロールして読みます
- 保存するのは新しい **30 件** まで。それを超えると古いものから消えます
- **`/clear` と送ると、保存されているメモを全部消します。**
  `クリア` `clear` `消す` `削除` なども同じ扱いで、先頭のスラッシュ（`/` `／`）と
  英字の大小は無視します
- 長文は 500 文字で切り詰められます

## セキュリティ

| 対策 | 内容 |
|---|---|
| Webhook 署名検証 | `x-line-signature` を channel secret で HMAC-SHA256 検証。検証しないと誰でも壁に文字を出せる |
| 送信者の許可リスト | 登録した LINE ユーザー ID 以外のメッセージは保存しない |
| 端末トークン | `/memo` は `Authorization: Bearer` 必須。比較は定数時間 |
| タブレット側 | 着信を受けず、外向きポーリングのみ |
| LAN 公開時のマスキング | タブレットの `/api/state` は、未認証の LAN クライアントにはメモ本文を返さない |

## ローカルで試す

```bash
cp .dev.vars.example .dev.vars
```

`.dev.vars` にテスト用の値を入れてから:

```bash
npx wrangler dev
```

## 運用コスト

Cloudflare Workers と KV の無料枠（1 日 10 万リクエスト）で十分収まります。
タブレットが 30 秒間隔でポーリングしても 1 日あたり約 2,880 リクエストです。
