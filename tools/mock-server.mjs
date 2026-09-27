#!/usr/bin/env node
/**
 * PC や LAN から開く「Web の設定画面」を、タブレットなしで作るためのモックサーバー（依存ゼロ）。
 *
 *   node tools/mock-server.mjs        → http://localhost:8080/settings
 *
 * API は実機（app/src/main/kotlin/app/walldash/server/DashboardServer.kt）と同じ形で返す。
 * アクセント色と通知音の選択肢は、アプリの Choices.kt を読んで作る（二重に書かない）。
 * タブレットのダッシュボード画面はアプリ（Compose）側にあるので、ここでは出ない。
 */
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = join(fileURLToPath(new URL(".", import.meta.url)), "..");
const ROOT = join(REPO, "app", "src", "main", "assets", "web");
const PORT = Number(process.env.PORT ?? 8080);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
};

function readChoices() {
  const kt = readFileSync(join(REPO, "app/src/main/kotlin/app/walldash/data/Choices.kt"), "utf8");
  const accents = [...kt.matchAll(/Accent\("(#[0-9A-Fa-f]{6})", "([^"]+)"\)/g)].map((m) => ({ value: m[1], label: m[2] }));
  const tones = [...kt.matchAll(/Tone\("([a-z]+)", "([^"]+)"/g)].map((m) => ({ value: m[1], label: m[2] }));
  return { accents, tones };
}

// Kotlin の PublicConfig と同じ形。Models.kt に項目を足したらここにも足すこと
// （欠けていても設定画面は動いてしまうので、違いに気づきにくい）。
let config = {
  configVersion: 1,
  lan: { enabled: false, pinSet: false },
  location: { configured: true, name: "東京", latitude: 35.6895, longitude: 139.6917, timezone: "Asia/Tokyo" },
  units: { temperature: "c", wind: "kmh", clock24h: true, showSeconds: true },
  display: {
    showClock: true, showWifi: true, showWeather: true, showHourly: true, showDaily: true, showSun: true,
    accent: "#4DD4FF", theme: "dark", cardOpacity: 0.6, normalBrightness: 1.0, burnInShiftEnabled: true,
    idleDimEnabled: true, idleDimAfterSeconds: 300, idleDimBrightness: 0.15,
    showDisaster: true, showFeed: true, showDeviceStats: true, showMemo: true,
    showTimer: true, showWord: true, showSpotify: true, showHamster: true,
    clockAlign: "left", clockDateFormat: "ja",
    weatherFields: ["apparent", "pm25", "pop", "rain", "humidity", "wind", "uv", "aqi", "visibility"],
    disasterShowTyphoon: true, disasterShowVolcano: true, disasterShowKmoni: true,
    hourlyMode: "both", spotifyShowControls: true, spotifyShowProgress: true, wifiShowGlobe: true,
    showTrain: false, showToday: false, showRadar: false, showCalendar: false,
    showStocks: false, showSunMoon: false, showCountdown: false, showAnalogClock: false,
    radarZoom: 8, todayShowEvent: true, analogSweep: true, analogNumerals: true,
  },
  refresh: { wifiIntervalMs: 2000, weatherIntervalMs: 600000 },
  disaster: { enabled: true, minIntensity: "3" },
  feed: { enabled: true, urls: ["https://example.com/rss.xml"], maxItems: 6 },
  memo: { enabled: true, endpoint: "https://example.workers.dev/memo", tokenSet: true, pollIntervalMs: 30000 },
  spotify: { enabled: false, clientId: "", connected: false },
  notifications: {
    disasterSound: true, chargingSound: true, volume: 0.7,
    disasterTone: "chime", chargingTone: "rise", timerTone: "beep",
  },
  wallpaper: { imageSetAt: 0 },
  train: { enabled: false, tokenSet: false, challengeTokenSet: false, railways: [] },
  calendar: { enabled: false, mode: "caldav", appleId: "", passwordSet: false, icsUrlSet: false, daysAhead: 7 },
  stocks: {
    symbols: [
      { symbol: "^N225", label: "日経平均" }, { symbol: "^DJI", label: "NY ダウ" },
      { symbol: "^IXIC", label: "ナスダック" }, { symbol: "USDJPY=X", label: "ドル円" },
    ],
    range: "1d",
  },
  countdown: { builtins: ["newyear", "christmas", "holiday", "fullmoon"], custom: [] },
  choices: readChoices(),
};

let launcherHome = false;

const json = (res, status, body) => {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
};

/*
 * カードの並べ方と、画面に収まるか（Kotlin の CardLayout と同じ計算）。
 * モックは横向き 1280x800dp の端末を想定する（並べられる高さ 740dp、1 行 160dp 以上 → 4 行まで）。
 * 幅（span）と最小の幅（min）を変えたら、CardLayout.Card と一緒に直すこと。
 */
const CARDS = [
  ["CLOCK", "showClock", 8, 6, "時刻"], ["WEATHER", "showWeather", 8, 7, "天気"], ["DISASTER", "showDisaster", 8, 7, "防災"],
  ["MEMO", "showMemo", 10, 5, "LINE メモ"], ["HOURLY", "showHourly", 9, 7, "時間別予報"], ["SPOTIFY", "showSpotify", 5, 5, "Spotify"],
  ["WIFI", "showWifi", 6, 6, "Wi-Fi"], ["STATS", "showDeviceStats", 10, 7, "端末状態"], ["NEWS", "showFeed", 8, 5, "ニュース"],
  ["DAILY", "showDaily", 12, 6, "週間予報"], ["TIMER", "showTimer", 6, 5, "タイマー"], ["WORD", "showWord", 6, 4, "今日の単語"],
  ["ANALOG_CLOCK", "showAnalogClock", 6, 4, "アナログ時計"], ["CALENDAR", "showCalendar", 9, 6, "予定表"], ["TRAIN", "showTrain", 9, 6, "運行情報"],
  ["RADAR", "showRadar", 8, 5, "雨雲レーダー"], ["SUN_MOON", "showSunMoon", 8, 7, "日の出・月"], ["COUNTDOWN", "showCountdown", 8, 6, "カウントダウン"],
  ["TODAY", "showToday", 8, 6, "今日は何の日"], ["STOCKS", "showStocks", 10, 6, "株価"],
].map(([id, key, span, min, label]) => ({ id, key, span, min, label }));
const CARD = Object.fromEntries(CARDS.map((c) => [c.id, c]));
config.choices.cards = CARDS.map(({ id, label, span, min }) => ({ id, label, span, min }));
config.choices.layoutRows = 4;
config.choices.columns = 24;
config.display.cardLayout = [];
// 後から足したカードは既定で非表示（display に無ければ false とみなす）
const DEFAULT_OFF = new Set(["showAnalogClock", "showCalendar", "showTrain", "showRadar", "showSunMoon", "showCountdown", "showToday", "showStocks"]);
const isShown = (d, key) => (DEFAULT_OFF.has(key) ? d[key] === true : d[key] !== false);
const shown = (d) => CARDS.filter((c) => isShown(d, c.key));
// 収まらないときの表示を試すなら MOCK_MAX_ROWS=3 node tools/mock-server.mjs
const MOCK_MAX_ROWS = Number(process.env.MOCK_MAX_ROWS ?? 4);
const LIMIT = Math.min(4, MOCK_MAX_ROWS);
const sum = (row) => row.reduce((a, x) => a + x.span, 0);
const slot = (card, span) => ({ card: card.id, span });
// Kotlin の trimEnd(): 末尾の空の行だけ落とす（途中の空の行は置き場所を保つため残す）
const trimEnd = (rows) => { const out = rows.slice(); while (out.length && !out[out.length - 1].length) out.pop(); return out; };

// Kotlin の pack(): 行に詰め、余りは同じ行のカードへ元の幅に比例して配る。backfill のカードの行に空きがあれば後ろのカードを戻す
const pack = (items, backfill) => {
  const rows = [];
  let home = -1;
  for (const it of items) {
    let target;
    if (rows.length && sum(rows[rows.length - 1]) + it.span <= 24) target = rows.length - 1;
    else if (home >= 0 && sum(rows[home]) + it.span <= 24) target = home;
    else { rows.push([]); target = rows.length - 1; }
    rows[target].push({ ...it });
    if (backfill && it.card === backfill) home = target;
  }
  return rows.map((row) => {
    const total = sum(row), extra = 24 - total;
    if (extra <= 0) return row;
    const exact = row.map((x) => (extra * x.span) / total);
    const out = row.map((x, i) => ({ ...x, span: x.span + Math.floor(exact[i]) }));
    const left = extra - exact.reduce((a, e) => a + Math.floor(e), 0);
    exact.map((e, i) => [e - Math.floor(e), i]).sort((p, q) => q[0] - p[0]).slice(0, left).forEach(([, i]) => (out[i].span += 1));
    return out;
  });
};
// Kotlin の autoRows(): 溢れるときは週間予報を半分（6 列）まで縮め、空いた列に後ろのカードを並べる
const autoRows = (d, maxRows = MOCK_MAX_ROWS) => {
  const cards = shown(d);
  const plain = pack(cards.map((c) => slot(c, c.span)));
  if (plain.length <= maxRows || !cards.includes(CARD.DAILY)) return plain;
  for (let span = 12; span >= 6; span--) {
    const items = cards.map((c) => slot(c, c === CARD.DAILY ? span : c.span));
    if (span < 12) { const r = pack(items); if (r.length <= maxRows) return r; }
    const r = pack(items, "DAILY");
    if (r.length <= maxRows) return r;
  }
  return plain;
};
// Kotlin の slots() / fitRow(): 知らないカード・重複を落とし、幅を最小〜24 に、行の合計が 24 を超えたら縮める
const slots = (layout) => {
  const seen = new Set();
  return (layout || []).map((row) => row.filter((x) => CARD[x.card] && !seen.has(x.card) && seen.add(x.card))
    .map((x) => ({ card: x.card, span: Math.min(24, Math.max(CARD[x.card].min, x.span)) })));
};
const fitRow = (row) => {
  const out = row.map((x) => ({ ...x }));
  const excess = (x) => x.span - CARD[x.card].min;
  while (sum(out) > 24) {
    const w = out.filter((x) => excess(x) > 0).sort((p, q) => excess(q) - excess(p))[0];
    if (!w) break;
    w.span--;
  }
  return out;
};
// Kotlin の place() / squeeze()
const squeeze = (row, card) => {
  const out = row.map((x) => ({ ...x }));
  const excess = (x) => x.span - CARD[x.card].min;
  const widest = () => out.slice().sort((p, q) => excess(q) - excess(p))[0];
  while (24 - sum(out) < card.min) widest().span--;
  let span = card.min;
  while (span < card.span) {
    if (24 - sum(out) - span > 0) { span++; continue; }
    const w = widest();
    if (!w || excess(w) <= span - card.min + 1) break;
    w.span--;
    span++;
  }
  return [...out, slot(card, span)];
};
const place = (rows, card, limit) => {
  const free = (row) => 24 - sum(row);
  const slack = (row) => 24 - row.reduce((a, x) => a + CARD[x.card].min, 0);
  const fits = rows.findIndex((row) => free(row) >= card.span);
  if (fits >= 0) return rows.map((row, i) => (i === fits ? [...row, slot(card, card.span)] : row));
  if (rows.length < limit) return [...rows, [slot(card, card.span)]];
  const idx = rows.map((row, i) => [slack(row), i]).filter(([s]) => s >= card.min).sort((p, q) => q[0] - p[0])[0];
  if (!idx) return null;
  return rows.map((row, i) => (i === idx[1] ? squeeze(row, card) : row));
};
// Kotlin の arranged(): 利用者の配置を表示するカードに合わせる
const arranged = (d) => {
  const show = new Set(shown(d).map((c) => c.id));
  let rows = trimEnd(slots(d.cardLayout).map((row) => fitRow(row.filter((x) => show.has(x.card)))));
  const placed = new Set(rows.flat().map((x) => x.card));
  for (const c of shown(d)) if (!placed.has(c.id)) rows = place(rows, c, LIMIT) ?? [...rows, [slot(c, c.span)]];
  return rows;
};
const editorRows = (d) => (d.cardLayout?.length ? arranged(d) : autoRows(d));
const fits = (d) => (d.cardLayout?.length ? arranged(d).filter((row) => row.length).length <= LIMIT : autoRows(d).length <= MOCK_MAX_ROWS);
// Kotlin の adjust()
const adjust = (before, after) => {
  const added = shown(after).filter((c) => !isShown(before, c.key));
  if (!after.cardLayout?.length && (!added.length || fits(after))) return { display: after, message: null };
  const show = new Set(shown(after).map((c) => c.id));
  let rows = (after.cardLayout?.length ? slots(after.cardLayout) : autoRows(before))
    .map((row) => fitRow(row.filter((x) => show.has(x.card))));
  rows = trimEnd(rows);
  const failed = [];
  const placed = new Set(rows.flat().map((x) => x.card));
  for (const c of shown(after)) {
    if (placed.has(c.id)) continue;
    const next = place(rows, c, LIMIT);
    if (next) rows = next; else { failed.push(c); rows = [...rows, [slot(c, c.span)]]; }
  }
  const display = { ...after, cardLayout: trimEnd(rows) };
  if (!added.length || (!failed.length && rows.filter((row) => row.length).length <= LIMIT)) return { display, message: null };
  const names = (failed.length ? failed : added).map((c) => c.label).join("」「");
  return {
    display,
    message: `「${names}」は、ほかのカードをいちばん狭い幅まで縮めても画面に入りません（この画面に並べられるのは ${LIMIT} 行までです）。ほかのカードを非表示にしてから、もう一度表示してください。`,
  };
};
const autoMessage = (d) => {
  const auto = { ...d, cardLayout: [] };
  const rows = autoRows(auto).length;
  if (rows <= MOCK_MAX_ROWS) return null;
  return `自動の並べ方では、いまのカードが ${rows} 行になり画面に収まりません（この画面に並べられるのは ${MOCK_MAX_ROWS} 行までです）。ほかのカードを非表示にしてから、もう一度お試しください。`;
};

const drain = (req) => new Promise((resolve) => { req.on("data", () => {}); req.on("end", resolve); });

const readBody = (req) => new Promise((resolve) => {
  let data = "";
  req.on("data", (c) => (data += c));
  req.on("end", () => { try { resolve(data ? JSON.parse(data) : {}); } catch { resolve({}); } });
});

const state = () => ({
  serverTime: Date.now(),
  deviceTimezone: "Asia/Tokyo",
  // 設定画面が読むのは防災の地域だけ
  disaster: { available: true, officeName: "東京都", areaName: "新宿区" },
  train: { lines: [], fetchedAt: 0, lastError: null },
  calendar: { events: [], fetchedAt: 0, lastError: null },
  config,
});

const device = () => ({
  launcherHomeEnabled: launcherHome,
  boundHost: config.lan.enabled ? "0.0.0.0" : "127.0.0.1",
  port: PORT,
  activeSessions: 0,
  pinSet: config.lan.pinSet,
  lanUrl: `http://192.168.1.42:${PORT}/settings`,
});

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const post = req.method === "POST";

  try {
    if (path === "/api/state" || (path === "/api/refresh" && post)) return json(res, 200, state());
    if (path === "/api/settings" && !post) return json(res, 200, config);
    if (path === "/api/settings" && post) {
      // SettingsController.saveAll と同じく、settings の各項目は「まるごと差し替え」
      const { settings = {}, memo, spotify, train, calendar } = await readBody(req);
      if (settings.display) {
        const adjusted = adjust(config.display, settings.display);
        if (adjusted.message) return json(res, 400, { error: "cards_overflow", detail: adjusted.message });
        settings.display = adjusted.display;
      }
      config = { ...config, configVersion: config.configVersion + 1 };
      for (const key of ["location", "units", "display", "refresh", "disaster", "feed", "notifications", "stocks", "countdown"]) {
        if (settings[key]) config[key] = settings[key];
      }
      if (memo) {
        const { token, ...rest } = memo;
        config.memo = { ...config.memo, ...rest, tokenSet: token === undefined ? config.memo.tokenSet : token !== "" };
      }
      if (spotify) config.spotify = { ...config.spotify, ...spotify };
      if (train) {
        const { token, challengeToken, ...rest } = train;
        config.train = {
          ...config.train, ...rest,
          tokenSet: token === undefined ? config.train.tokenSet : token !== "",
          challengeTokenSet: challengeToken === undefined ? config.train.challengeTokenSet : challengeToken !== "",
        };
      }
      if (calendar) {
        const { password, icsUrl, ...rest } = calendar;
        config.calendar = {
          ...config.calendar, ...rest,
          passwordSet: password === undefined ? config.calendar.passwordSet : password !== "",
          icsUrlSet: icsUrl === undefined ? config.calendar.icsUrlSet : icsUrl !== "",
        };
      }
      return json(res, 200, config);
    }
    if (path === "/api/train/railways" || (path === "/api/train/railways/reload" && post)) {
      return json(res, 200, [
        { id: "odpt.Railway:TokyoMetro.Ginza", title: "銀座線", operator: "東京メトロ" },
        { id: "odpt.Railway:TokyoMetro.Marunouchi", title: "丸ノ内線", operator: "東京メトロ" },
        { id: "odpt.Railway:Toei.Asakusa", title: "浅草線", operator: "都営地下鉄" },
      ]);
    }
    if (path === "/api/layout/check" && post) {
      const { before = {}, after = {} } = await readBody(req);
      const adjusted = adjust(before, after);
      const display = adjusted.message ? before : adjusted.display;
      return json(res, 200, {
        ok: !adjusted.message, message: adjusted.message,
        layout: display.cardLayout ?? [], rows: editorRows(display), autoMessage: autoMessage(display),
      });
    }
    if (path === "/api/wallpaper" && post) {
      await drain(req);
      config.wallpaper = { imageSetAt: Date.now() };
      return json(res, 200, config);
    }
    if (path === "/api/wallpaper/clear" && post) {
      config.wallpaper = { imageSetAt: 0 };
      return json(res, 200, config);
    }
    if (path === "/api/device") {
      if (post) launcherHome = !!(await readBody(req)).launcherHomeEnabled;
      return json(res, 200, device());
    }
    if (path === "/api/lan" && post) {
      const body = await readBody(req);
      if (body.pin !== undefined) {
        if (String(body.pin).length < 6) return json(res, 400, { error: "pin_too_short", detail: "PIN は 6 桁以上必要です" });
        config.lan = { ...config.lan, pinSet: true };
      }
      if (body.enabled !== undefined) {
        if (body.enabled && !config.lan.pinSet) {
          return json(res, 400, { error: "pin_required", detail: "LAN 公開を有効にする前に PIN を設定してください" });
        }
        config.lan = { ...config.lan, enabled: !!body.enabled };
      }
      return json(res, 200, config);
    }
    if (path === "/api/sound/preview" && post) {
      console.log(`[mock] 試聴: ${url.searchParams.get("tone")}（音量 ${url.searchParams.get("volume")}）`);
      return json(res, 200, { ok: true });
    }
    if (path === "/api/spotify/disconnect" && post) {
      config.spotify = { ...config.spotify, connected: false };
      return json(res, 200, config);
    }
    if (path === "/api/geocode") {
      const q = url.searchParams.get("q") ?? "";
      try {
        const g = new URL("https://geocoding-api.open-meteo.com/v1/search");
        g.search = new URLSearchParams({ name: q, count: "8", language: "ja", format: "json" }).toString();
        const d = await (await fetch(g, { signal: AbortSignal.timeout(8000) })).json();
        return json(res, 200, (d.results ?? []).map((x) => ({
          name: x.name, admin: x.admin1, country: x.country,
          latitude: x.latitude, longitude: x.longitude, timezone: x.timezone ?? "auto",
        })));
      } catch {
        return json(res, 200, [{ name: q || "モック地点", admin: null, country: "JP", latitude: 35.0, longitude: 135.0, timezone: "Asia/Tokyo" }]);
      }
    }

    let rel = path === "/" || path === "/settings" ? "settings.html" : path.replace(/^\/static\//, "");
    rel = normalize(rel).replace(/^(\.\.[/\\])+/, "");
    const file = join(ROOT, rel);
    const body = await readFile(file);
    res.writeHead(200, { "Content-Type": MIME[extname(file)] ?? "application/octet-stream", "Cache-Control": "no-store" });
    res.end(body);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end(`not found: ${path}`);
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`[mock] http://localhost:${PORT}/settings`);
});
