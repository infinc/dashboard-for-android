/**
 * Walldash — LINE メモ中継 Worker
 *
 * なぜ中継が要るのか:
 *   LINE Messaging API は公開された HTTPS の Webhook にしかメッセージを届けない。
 *   壁掛けタブレットは家庭内 NAT の内側にあり、設計上 loopback しか待ち受けない。
 *   また LINE には「Bot 宛のメッセージを後から取得する」API が無いため、
 *   受け取って保持する場所がどこかに必要になる。
 *
 * 流れ:
 *   LINE アプリ → LINE Platform → この Worker(/line/webhook) → KV に保存
 *                                        ↑
 *                     タブレットが /memo を外向きにポーリング
 *
 * タブレットは一切着信を受けない。
 */

export interface Env {
  MEMOS: KVNamespace;
  /** LINE チャネルシークレット。Webhook 署名の検証に使う。 */
  LINE_CHANNEL_SECRET: string;
  /** LINE チャネルアクセストークン。返信と表示名取得に使う。 */
  LINE_CHANNEL_ACCESS_TOKEN: string;
  /** タブレットが /memo を読むためのトークン。 */
  DEVICE_TOKEN: string;
  /** 受け付ける LINE ユーザー ID をカンマ区切りで。未設定なら誰も登録できない（初回案内のみ返す）。 */
  ALLOWED_USER_IDS?: string;
}

interface StoredMemo {
  /** 個々のメモを識別する。タブレット側が差分を検出して再描画を抑えるために使う。 */
  id: string;
  text: string;
  senderName: string | null;
  receivedAt: number;
}

/** 旧版は最新の 1 件だけをここに置いていた。初回だけ読み出して一覧へ移す。 */
const LEGACY_KEY = "memo:latest";
const LIST_KEY = "memo:list";
/** 壁に出すのは新しい数件だけ。KV の 1 値あたりの上限も考えて件数を抑える。 */
const MAX_MEMOS = 30;
const CLEAR_WORDS = ["クリア", "clear", "けす", "消す", "削除", "ぜんぶけす", "全部消す"];

/**
 * 消去コマンドかどうか。
 * LINE では "/clear" のようにスラッシュ付きで打つ人が多く、日本語入力だと "／" になる。
 * どちらも同じ意図なので、先頭の記号を落としてから比較する。
 */
function isClearCommand(text: string): boolean {
  const normalized = text.trim().replace(/^[/／]+/, "").toLowerCase();
  return CLEAR_WORDS.includes(normalized);
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/line/webhook" && request.method === "POST") {
      return handleWebhook(request, env, ctx);
    }
    if (url.pathname === "/memo" && request.method === "GET") {
      return handleMemo(request, env);
    }
    if (url.pathname === "/health") {
      return new Response("ok", { headers: { "content-type": "text/plain" } });
    }
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;

// ---------------------------------------------------------------- Webhook

async function handleWebhook(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const raw = await request.text();
  const signature = request.headers.get("x-line-signature");

  // 署名検証は必須。これが無いと URL を知る者が誰でも壁に文字を出せる。
  if (!signature || !(await verifySignature(raw, signature, env.LINE_CHANNEL_SECRET))) {
    return new Response("invalid signature", { status: 401 });
  }

  let payload: { events?: LineEvent[] };
  try {
    payload = JSON.parse(raw);
  } catch {
    return new Response("bad request", { status: 400 });
  }

  // LINE には即座に 200 を返す必要があるため、処理は waitUntil に逃がす。
  ctx.waitUntil(processEvents(payload.events ?? [], env));
  return new Response("ok");
}

async function processEvents(events: LineEvent[], env: Env): Promise<void> {
  const allowed = (env.ALLOWED_USER_IDS ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);

  for (const event of events) {
    if (event.type !== "message" || event.message?.type !== "text") continue;

    const userId = event.source?.userId ?? "";
    const text = (event.message.text ?? "").trim();
    if (!text) continue;

    // 初期設定の導線: 許可リストが空、または未登録の相手には
    // 「これがあなたの ID です」と返すだけで、メモは保存しない。
    if (allowed.length === 0 || !allowed.includes(userId)) {
      await reply(
        env,
        event.replyToken,
        `このユーザーIDはまだ許可されていません。\n\n${userId}\n\nこのIDを ALLOWED_USER_IDS に登録してください。`,
      );
      continue;
    }

    if (isClearCommand(text)) {
      const removed = (await loadMemos(env)).length;
      await env.MEMOS.put(LIST_KEY, "[]");
      // 旧キーが残っていると移行処理が消したメモを復活させてしまう
      await env.MEMOS.delete(LEGACY_KEY);
      await reply(
        env,
        event.replyToken,
        removed > 0 ? `メモを ${removed} 件すべて消しました。` : "消すメモはありませんでした。",
      );
      continue;
    }

    const memo: StoredMemo = {
      id: crypto.randomUUID(),
      // 壁に出すだけなので長文は切り詰める
      text: text.slice(0, 500),
      senderName: await fetchDisplayName(env, userId),
      receivedAt: Date.now(),
    };
    // 新しいものが先頭。壁では上から読めた方が自然で、古いものから押し出される。
    const memos = [memo, ...(await loadMemos(env))].slice(0, MAX_MEMOS);
    await env.MEMOS.put(LIST_KEY, JSON.stringify(memos));
    await reply(
      env,
      event.replyToken,
      memos.length > 1
        ? `ダッシュボードに表示しました。（${memos.length} 件）\n全部消すには /clear と送ってください。`
        : "ダッシュボードに表示しました。",
    );
  }
}

/**
 * 保存済みのメモを新しい順で返す。
 * 旧版の単独キーしか無い環境でも 1 件目として拾えるようにしておく。
 */
async function loadMemos(env: Env): Promise<StoredMemo[]> {
  const list = await env.MEMOS.get<StoredMemo[]>(LIST_KEY, "json");
  if (Array.isArray(list)) return list.filter((m) => m && typeof m.text === "string");

  const legacy = await env.MEMOS.get<Partial<StoredMemo>>(LEGACY_KEY, "json");
  if (!legacy?.text) return [];
  return [
    {
      id: legacy.id ?? `legacy-${legacy.receivedAt ?? 0}`,
      text: legacy.text,
      senderName: legacy.senderName ?? null,
      receivedAt: legacy.receivedAt ?? 0,
    },
  ];
}

// ---------------------------------------------------------------- タブレット向け

async function handleMemo(request: Request, env: Env): Promise<Response> {
  const header = request.headers.get("authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";

  // トークンが無ければ URL を知る者が全員メモを読めてしまう。
  if (!env.DEVICE_TOKEN || !timingSafeEqual(token, env.DEVICE_TOKEN)) {
    return json({ error: "unauthorized" }, 401);
  }

  const memos = await loadMemos(env);
  const latest = memos[0];
  // text / senderName / receivedAt は旧タブレット向けの互換フィールド。
  // 新しい端末は memos 配列だけを見る。
  return json({
    memos,
    text: latest?.text ?? null,
    senderName: latest?.senderName ?? null,
    receivedAt: latest?.receivedAt ?? 0,
  });
}

// ---------------------------------------------------------------- LINE API

async function reply(env: Env, replyToken: string | undefined, text: string): Promise<void> {
  if (!replyToken || !env.LINE_CHANNEL_ACCESS_TOKEN) return;
  try {
    await fetch("https://api.line.me/v2/bot/message/reply", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${env.LINE_CHANNEL_ACCESS_TOKEN}`,
      },
      body: JSON.stringify({ replyToken, messages: [{ type: "text", text }] }),
    });
  } catch (e) {
    console.warn("LINE への返信に失敗", e);
  }
}

async function fetchDisplayName(env: Env, userId: string): Promise<string | null> {
  if (!userId || !env.LINE_CHANNEL_ACCESS_TOKEN) return null;
  try {
    const res = await fetch(`https://api.line.me/v2/bot/profile/${encodeURIComponent(userId)}`, {
      headers: { authorization: `Bearer ${env.LINE_CHANNEL_ACCESS_TOKEN}` },
    });
    if (!res.ok) return null;
    const profile = (await res.json()) as { displayName?: string };
    return profile.displayName ?? null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- 署名・比較

async function verifySignature(body: string, signature: string, secret: string): Promise<boolean> {
  if (!secret) return false;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(body));
  const expected = btoa(String.fromCharCode(...new Uint8Array(mac)));
  return timingSafeEqual(signature, expected);
}

/** 文字列比較で早期 return しない。長さの違いだけは漏れるが、内容は漏らさない。 */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---------------------------------------------------------------- 型

interface LineEvent {
  type?: string;
  replyToken?: string;
  source?: { userId?: string };
  message?: { type?: string; text?: string };
}
