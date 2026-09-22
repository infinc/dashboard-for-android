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
    accent: "#4DD4FF", normalBrightness: 1.0, burnInShiftEnabled: true,
    idleDimEnabled: true, idleDimAfterSeconds: 300, idleDimBrightness: 0.15,
    showDisaster: true, showFeed: true, showDeviceStats: true, showMemo: true,
    showTimer: true, showWord: true, showSpotify: true, showHamster: true,
    clockAlign: "left", clockDateFormat: "ja",
    weatherFields: ["apparent", "pm25", "pop", "rain", "humidity", "wind", "uv", "aqi", "visibility"],
    disasterShowTyphoon: true, disasterShowVolcano: true, disasterShowKmoni: true,
    hourlyMode: "both", spotifyShowControls: true, spotifyShowProgress: true, wifiShowGlobe: true,
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
  choices: readChoices(),
};

let launcherHome = false;

const json = (res, status, body) => {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
};

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
      const { settings = {}, memo, spotify } = await readBody(req);
      config = { ...config, configVersion: config.configVersion + 1 };
      for (const key of ["location", "units", "display", "refresh", "disaster", "feed", "notifications"]) {
        if (settings[key]) config[key] = settings[key];
      }
      if (memo) {
        const { token, ...rest } = memo;
        config.memo = { ...config.memo, ...rest, tokenSet: token === undefined ? config.memo.tokenSet : token !== "" };
      }
      if (spotify) config.spotify = { ...config.spotify, ...spotify };
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
