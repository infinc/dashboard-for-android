#!/usr/bin/env node
/**
 * Mac 上で assets/web のダッシュボード UI を開発するためのモックサーバー（依存ゼロ）。
 *
 *   node tools/mock-server.mjs        → http://localhost:8080
 *
 * 端末に APK を入れ直さずにブラウザで UI を詰めるためのもの。
 * /api/state は Android 側と同じ形の JSON を返す。天気は実際の Open-Meteo を叩き、
 * 取得できない場合は合成データにフォールバックする（オフラインでも UI が崩れないことを確認できる）。
 */
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL(".", import.meta.url)), "..", "app", "src", "main", "assets", "web");
const PORT = Number(process.env.PORT ?? 8080);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".woff2": "font/woff2",
};

let config = {
  configVersion: 1,
  lan: { enabled: false, pinSet: false },
  location: { configured: true, name: "東京", latitude: 35.6895, longitude: 139.6917, timezone: "Asia/Tokyo" },
  units: { temperature: "c", wind: "kmh", clock24h: true, showSeconds: true },
  display: {
    layout: "balanced", showClock: true, showWifi: true, showWeather: true,
    showHourly: true, showDaily: true, showSun: true, accent: "#4DD4FF",
    normalBrightness: 1.0, burnInShiftEnabled: true,
    idleDimEnabled: true, idleDimAfterSeconds: 300, idleDimBrightness: 0.15,
    showDisaster: true, showFeed: true, showDeviceStats: true, showMemo: true,
    showTimer: true, showWord: true, showSpotify: true,
  },
  refresh: { wifiIntervalMs: 2000, weatherIntervalMs: 600000 },
  disaster: { enabled: true, officeCode: "130000", officeName: "東京都", minIntensity: "3" },
  spotify: { enabled: true, clientId: "mock-client-id", connected: true },
  feed: { enabled: true, urls: ["https://example.com/rss"], maxItems: 6 },
  memo: { enabled: true, endpoint: "https://example.workers.dev/memo", tokenSet: true, pollIntervalMs: 30000 },
};

// ---- 端末状態: 実機に近い値 ----
let battery = 92;
let cpu = 18;
let temp = 31.5;
function mockStats() {
  battery += (Math.random() - 0.45) * 0.3;
  battery = Math.max(20, Math.min(100, battery));
  // CPU はときどき跳ねる。グラフの形を確認するため。
  cpu += (Math.random() - 0.5) * 14;
  if (Math.random() < 0.05) cpu += 35;
  cpu = Math.max(1, Math.min(100, cpu));
  temp += (Math.random() - 0.5) * 0.4;
  temp = Math.max(24, Math.min(48, temp));
  return {
    batteryPercent: Math.round(battery),
    // CHARGING=0 で放電中の表示を確認できる
    charging: process.env.CHARGING !== "0",
    cpuPercent: Math.round(cpu),
    batteryTemperatureC: Math.round(temp * 10) / 10,
    storageFreeBytes: 19.4 * 1024 ** 3,
    storageTotalBytes: 29.1 * 1024 ** 3,
    memoryAvailableBytes: 0.74 * 1024 ** 3,
    memoryTotalBytes: 1.86 * 1024 ** 3,
    // 実機同様、待機中はほぼ流れず、ときどき大きく跳ねる
    rxBitsPerSec: Math.round(Math.random() < 0.15 ? 4e6 + Math.random() * 3e7 : 3e4 + Math.random() * 4e5),
    txBitsPerSec: Math.round(Math.random() < 0.15 ? 5e5 + Math.random() * 4e6 : 1e4 + Math.random() * 9e4),
  };
}

// ---- 防災: 実際の気象庁 JSON を叩く ----
let disasterCache = null, disasterAt = 0, areaNames = null;
async function getAreaNames() {
  if (areaNames) return areaNames;
  try {
    const a = await fetch("https://www.jma.go.jp/bosai/common/const/area.json",
      { signal: AbortSignal.timeout(10000) }).then((r) => r.json());
    areaNames = {};
    for (const group of ["offices", "class15s", "class20s", "class10s"]) {
      for (const [code, v] of Object.entries(a[group] ?? {})) areaNames[code] = v.name;
    }
  } catch { areaNames = {}; }
  return areaNames;
}
async function getDisaster() {
  if (disasterCache && Date.now() - disasterAt < 300_000) return disasterCache;
  try {
    const j = (u) => fetch(u, { signal: AbortSignal.timeout(8000) }).then((r) => r.json());
    const [w, q, ts, tcList, vol] = await Promise.all([
      j(`https://www.jma.go.jp/bosai/warning/data/warning/${config.disaster.officeCode}.json`),
      j("https://www.jma.go.jp/bosai/quake/data/list.json"),
      j("https://www.jma.go.jp/bosai/tsunami/data/list.json").catch(() => []),
      j("https://www.jma.go.jp/bosai/typhoon/data/targetTc.json").catch(() => []),
      j("https://www.jma.go.jp/bosai/volcano/data/warning.json").catch(() => []),
    ]);
    const names = await getAreaNames();
    const areas = [];
    // 実機と同じく一次細分区域(areaTypes[0])だけを見る
    for (const a of (w.areaTypes ?? [])[0]?.areas ?? []) {
      const count = (a.warnings ?? []).filter((x) => x.code && x.status !== "解除").length;
      if (count > 0 && !areas.find((x) => x.code === a.code)) {
        areas.push({ code: a.code, name: names[a.code] ?? a.code, count });
      }
    }
    const seen = new Set();
    const quakeList = (q ?? [])
      .filter((e) => parseFloat(String(e.maxi || "0")) >= 1)
      .filter((e) => {
        const k = (e.at ?? e.rdt) + "|" + e.anm;
        if (seen.has(k)) return false;
        seen.add(k); return true;
      })
      .slice(0, 20)
      .map((e) => ({
        occurredAt: e.at ?? e.rdt, epicenter: e.anm,
        magnitude: e.mag, maxIntensity: e.maxi, title: e.ttl,
      }));
    // 台風は一覧に載った識別子ごとに詳細を引く。実機側と同じ組み立てにする。
    const typhoons = [];
    for (const t of (tcList ?? []).slice(0, 3)) {
      try {
        const spec = await j(`https://www.jma.go.jp/bosai/typhoon/data/${t.tropicalCyclone}/specifications.json`);
        const partName = (o) => (typeof o.part === "string" ? o.part : o.part?.jp);
        const title = spec.find((o) => partName(o) === "title") ?? {};
        const now = spec.find((o) => o.advancedHours === 0) ?? {};
        const num = title.typhoonNumber ?? t.typhoonNumber ?? "";
        typhoons.push({
          number: num.length >= 3 ? `台風${Number(num.slice(-2))}号` : null,
          name: title.name?.jp ?? null,
          scale: now.scale ?? null, intensity: now.intensity ?? null,
          location: now.location ?? null, pressureHpa: now.pressure ?? null,
          maxWindMps: now.maximumWind?.sustained?.["m/s"] ?? null,
          gustMps: now.maximumWind?.gust?.["m/s"] ?? null,
          course: now.course ?? null, speedKmh: now.speed?.["km/h"] ?? null,
          reportedAt: t.issue ?? null,
        });
      } catch { /* 1 つ取れなくても他は出す */ }
    }

    const volcanoes = [];
    for (const e of vol ?? []) {
      for (const info of e.volcanoInfos ?? []) {
        if (!String(info.type ?? "").includes("対象火山")) continue;
        for (const item of info.items ?? []) {
          for (const a of item.areas ?? []) {
            if (!a.name || volcanoes.find((x) => x.name === a.name)) continue;
            volcanoes.push({
              name: a.name, level: item.name, reportedAt: e.reportDatetime ?? null,
              severe: /レベル[３４５]|危険|避難/.test(item.name ?? ""),
            });
          }
        }
      }
    }
    volcanoes.sort((a, b) => String(b.reportedAt).localeCompare(String(a.reportedAt)));

    disasterCache = {
      available: true,
      officeName: config.disaster.officeName,
      headline: w.headlineText || null,
      reportedAt: w.reportDatetime ?? null,
      activeAreas: areas,
      quakes: quakeList,
      tsunami: (ts ?? []).slice(0, 3).map((e) => ({ title: e.ttl ?? null, reportedAt: e.rdt ?? e.at ?? null })),
      typhoons,
      volcanoes,
      fetchedAt: Date.now(), lastError: null,
    };
  } catch (e) {
    console.warn(`[mock] 気象庁の取得に失敗 (${e.message})`);
    disasterCache = {
      available: true, officeName: "東京都",
      headline: "東京地方では、強風や高波に注意してください。",
      reportedAt: new Date().toISOString(), activeAreas: [{ code: "130010", name: "東京地方", count: 2 }],
      quakes: [{ occurredAt: new Date().toISOString(), epicenter: "宮古島近海", magnitude: "4.4", maxIntensity: "3", title: "震源・震度情報" }],
      fetchedAt: Date.now(), lastError: null,
    };
  }
  disasterAt = Date.now();
  return disasterCache;
}

const MOCK_FEED = {
  items: [
    { title: "モック記事: 長めの見出しがカードの中で 2 行に収まるかを確認するためのサンプルテキストです", source: "サンプルニュース", publishedAt: null },
    { title: "モック記事: 短い見出し", source: "サンプルニュース", publishedAt: null },
    { title: "モック記事: 中くらいの長さの見出しを用意しておく", source: "別のフィード", publishedAt: null },
    { title: "モック記事: 四番目の項目", source: "別のフィード", publishedAt: null },
    { title: "モック記事: 五番目の項目", source: "サンプルニュース", publishedAt: null },
  ],
  fetchedAt: Date.now(),
  lastError: null,
};

// 複数メモのスクロールを確認できるだけの件数を入れておく
const MOCK_MEMO_ITEMS = [
  { id: "m1", text: "牛乳と卵を買ってきて。あと明日の燃えるゴミ忘れずに", senderName: "家族", receivedAt: Date.now() - 12 * 60000 },
  { id: "m2", text: "19時に駅まで迎えにきて", senderName: "家族", receivedAt: Date.now() - 48 * 60000 },
  { id: "m3", text: "金曜は学校の面談があるので早く帰ります", senderName: "家族", receivedAt: Date.now() - 3 * 3600_000 },
  { id: "m4", text: "宅配の再配達を19-21時で頼んでおいた", senderName: "家族", receivedAt: Date.now() - 6 * 3600_000 },
  { id: "m5", text: "折り返し確認用。かなり長めの本文を入れて、カードの中で何行になるか、スクロールが必要になるかを見る。", senderName: "家族", receivedAt: Date.now() - 26 * 3600_000 },
];

// MEMOS=1 のように件数を変えて、1 件だけのときの大きな表示も確認できるようにする
const MEMO_ITEMS = MOCK_MEMO_ITEMS.slice(0, Number(process.env.MEMOS ?? MOCK_MEMO_ITEMS.length));

const MOCK_MEMO = {
  items: MEMO_ITEMS,
  text: MEMO_ITEMS[0]?.text ?? null,
  senderName: MEMO_ITEMS[0]?.senderName ?? null,
  receivedAt: MEMO_ITEMS[0]?.receivedAt ?? 0,
  fetchedAt: Date.now(),
  lastError: null,
};

// ---- Wi-Fi: 実機同様にゆらぐ値を返す（UI のアニメーションを確認するため） ----
let rssi = -52;
function mockWifi() {
  rssi += (Math.random() - 0.5) * 3;
  rssi = Math.max(-88, Math.min(-35, rssi));
  const r = Math.round(rssi);
  const level = r >= -55 ? 4 : r >= -65 ? 3 : r >= -73 ? 2 : r >= -82 ? 1 : 0;
  // 実機の WifiInfo と同じく下り（rx）のほうが速いことが多いので、そう見える値を返す。
  const rx = Math.round(433 + (r + 60) * 6 + Math.random() * 20);
  const tx = Math.round(rx * (0.72 + Math.random() * 0.12));
  return {
    connected: true,
    ssid: "mock-wifi-5G",
    ssidStatus: "ok",
    linkSpeedMbps: tx,
    rxLinkSpeedMbps: rx,
    txLinkSpeedMbps: tx,
    rssiDbm: r,
    signalLevel: level,
    band: "5GHz",
    frequencyMhz: 5180,
    ipAddress: "192.168.1.42",
    updatedAt: Date.now(),
  };
}

// ---- Spotify: 実際には繋がないので、曲が切り替わる様子だけ再現する ----
const MOCK_TRACKS = [
  { trackName: "Bohemian Rhapsody", artistName: "Queen", albumName: "A Night at the Opera" },
  { trackName: "Blue Monday", artistName: "New Order", albumName: "Substance" },
  { trackName: "夜に駆ける", artistName: "YOASOBI", albumName: "THE BOOK" },
];
function mockSpotify() {
  // 30 秒ごとに次の曲へ。描き替えの挙動を確かめるため。
  const i = Math.floor(Date.now() / 30000) % MOCK_TRACKS.length;
  const t = MOCK_TRACKS[i];
  return {
    available: true,
    playing: true,
    trackName: t.trackName,
    artistName: t.artistName,
    albumName: t.albumName,
    // ジャケットは実画像を持たないので、色だけの SVG を data URL で返す
    albumImageUrl: "data:image/svg+xml;utf8," + encodeURIComponent(
      `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="300">
         <rect width="300" height="300" fill="hsl(${i * 110}, 45%, 38%)"/>
         <circle cx="150" cy="150" r="58" fill="rgba(0,0,0,0.35)"/>
         <circle cx="150" cy="150" r="14" fill="rgba(255,255,255,0.75)"/>
       </svg>`),
    progressMs: Date.now() % 200000,
    durationMs: 200000,
    fetchedAt: Date.now(),
    lastError: null,
  };
}

// ---- 天気: 実 API → 失敗時は合成 ----
let weatherCache = null;
let weatherFetchedAt = 0;

async function getWeather() {
  if (weatherCache && Date.now() - weatherFetchedAt < 600_000) return weatherCache;
  const { latitude, longitude, timezone, name } = config.location;
  try {
    const url = new URL("https://api.open-meteo.com/v1/forecast");
    url.search = new URLSearchParams({
      latitude, longitude, timezone, forecast_days: "7",
      current: "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m,wind_direction_10m,precipitation",
      hourly: "temperature_2m,precipitation_probability,weather_code,uv_index,visibility",
      daily: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,sunrise,sunset",
    }).toString();
    const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    weatherCache = shape(await res.json(), name);
    const air = await fetchAqi(latitude, longitude, timezone);
    weatherCache.current.aqi = air?.aqi ?? null;
    weatherCache.current.pm25 = air?.pm25 ?? null;
  } catch (e) {
    console.warn(`[mock] 天気の実取得に失敗 (${e.message}) — 合成データを使う`);
    weatherCache = synthetic(name);
  }
  weatherFetchedAt = Date.now();
  return weatherCache;
}

/** 大気質は別エンドポイント。落ちても天気は出せるよう握りつぶす。 */
async function fetchAqi(latitude, longitude, timezone) {
  try {
    const url = new URL("https://air-quality-api.open-meteo.com/v1/air-quality");
    url.search = new URLSearchParams({ latitude, longitude, timezone, current: "european_aqi,pm2_5" }).toString();
    const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const d = await r.json();
    return {
      aqi: d.current?.european_aqi == null ? null : Math.round(d.current.european_aqi),
      pm25: d.current?.pm2_5 ?? null,
    };
  } catch (e) {
    console.warn(`[mock] 大気質の取得に失敗 (${e.message})`);
    return null;
  }
}

function shape(d, placeName) {
  const hours = (d.hourly?.time ?? []).map((t, i) => ({
    time: t,
    temperature: d.hourly.temperature_2m?.[i] ?? null,
    precipitationProbability: d.hourly.precipitation_probability?.[i] ?? null,
    weatherCode: d.hourly.weather_code?.[i] ?? null,
  }));
  const startIdx = Math.max(0, hours.findIndex((h) => h.time >= (d.current?.time ?? "")));
  return {
    available: true,
    placeName,
    timezone: d.timezone,
    current: {
      temperature: d.current?.temperature_2m ?? null,
      apparentTemperature: d.current?.apparent_temperature ?? null,
      humidity: d.current?.relative_humidity_2m ?? null,
      windSpeed: d.current?.wind_speed_10m ?? null,
      windDirection: d.current?.wind_direction_10m == null ? null : Math.round(d.current.wind_direction_10m),
      precipitationProbability: hours[startIdx]?.precipitationProbability ?? null,
      precipitation: d.current?.precipitation ?? null,
      // uv_index / visibility は current に無いので時間別の同じ添字から拾う
      uvIndex: d.hourly?.uv_index?.[startIdx] ?? null,
      visibilityMeters: d.hourly?.visibility?.[startIdx] ?? null,
      aqi: null,
      pm25: null,
      weatherCode: d.current?.weather_code ?? null,
      isDay: (d.current?.is_day ?? 1) === 1,
    },
    hourly: hours.slice(startIdx, startIdx + 12),
    daily: (d.daily?.time ?? []).map((date, i) => ({
      date,
      tempMax: d.daily.temperature_2m_max?.[i] ?? null,
      tempMin: d.daily.temperature_2m_min?.[i] ?? null,
      weatherCode: d.daily.weather_code?.[i] ?? null,
      precipitationSum: d.daily.precipitation_sum?.[i] ?? null,
      precipitationProbabilityMax: d.daily.precipitation_probability_max?.[i] ?? null,
      sunrise: d.daily.sunrise?.[i] ?? null,
      sunset: d.daily.sunset?.[i] ?? null,
    })),
    fetchedAt: Date.now(),
    lastError: null,
  };
}

function synthetic(placeName) {
  const now = new Date();
  const iso = (dt) => dt.toISOString().slice(0, 16);
  const codes = [0, 1, 2, 3, 45, 61, 63, 71, 95];
  return {
    available: true,
    placeName,
    timezone: "Asia/Tokyo",
    current: {
      temperature: 21.4, apparentTemperature: 20.1, humidity: 58,
      windSpeed: 9.2, windDirection: 45, precipitationProbability: 10,
      precipitation: 0.4, uvIndex: 4.2, visibilityMeters: 18400, aqi: 34, pm25: 8.3,
      weatherCode: 2,
      isDay: now.getHours() >= 6 && now.getHours() < 18,
    },
    hourly: Array.from({ length: 12 }, (_, i) => {
      const t = new Date(now.getTime() + i * 3600_000);
      return {
        time: iso(t),
        temperature: 21 + Math.sin(i / 3) * 4,
        precipitationProbability: Math.round(Math.max(0, Math.sin(i / 2) * 55)),
        weatherCode: codes[i % codes.length],
      };
    }),
    daily: Array.from({ length: 7 }, (_, i) => {
      const d = new Date(now.getTime() + i * 86400_000);
      return {
        date: d.toISOString().slice(0, 10),
        tempMax: 24 + Math.sin(i) * 3,
        tempMin: 15 + Math.cos(i) * 2,
        weatherCode: codes[(i * 2) % codes.length],
        precipitationSum: Math.max(0, Math.sin(i) * 8).toFixed(1) * 1,
        precipitationProbabilityMax: Math.round(Math.max(0, Math.sin(i) * 80)),
        sunrise: `${d.toISOString().slice(0, 10)}T05:${30 + i}`,
        sunset: `${d.toISOString().slice(0, 10)}T18:${10 + i}`,
      };
    }),
    fetchedAt: Date.now(),
    lastError: null,
  };
}

// ---- HTTP ----
const json = (res, code, body) => {
  res.writeHead(code, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
};

const readBody = (req) => new Promise((resolve) => {
  let data = "";
  req.on("data", (c) => (data += c));
  req.on("end", () => { try { resolve(data ? JSON.parse(data) : {}); } catch { resolve({}); } });
});

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;

  try {
    if (path === "/api/state") {
      return json(res, 200, {
        serverTime: Date.now(),
        deviceTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        wifi: mockWifi(),
        weather: await getWeather(),
        deviceStats: mockStats(),
        disaster: await getDisaster(),
        spotify: mockSpotify(),
        feed: { ...MOCK_FEED, fetchedAt: Date.now() },
        memo: MOCK_MEMO,
        config,
        masked: false,
      });
    }
    if (path === "/api/settings" && req.method === "GET") return json(res, 200, config);
    if (path === "/api/settings" && req.method === "POST") {
      const patch = await readBody(req);
      config = {
        ...config,
        configVersion: config.configVersion + 1,
        location: patch.location ?? config.location,
        units: patch.units ?? config.units,
        display: patch.display ?? config.display,
        refresh: patch.refresh ?? config.refresh,
      };
      weatherCache = null;
      return json(res, 200, config);
    }
    if (path === "/api/device") {
      return json(res, 200, {
        launcherHomeEnabled: false, boundHost: "127.0.0.1", port: PORT,
        activeSessions: 0, pinSet: false,
      });
    }
    if (path === "/api/lan") { return json(res, 200, config); }
    if (path === "/api/geocode") {
      const q = url.searchParams.get("q") ?? "";
      try {
        const g = new URL("https://geocoding-api.open-meteo.com/v1/search");
        g.search = new URLSearchParams({ name: q, count: "8", language: "ja", format: "json" }).toString();
        const r = await fetch(g, { signal: AbortSignal.timeout(8000) });
        const d = await r.json();
        return json(res, 200, (d.results ?? []).map((x) => ({
          name: x.name, admin: x.admin1, country: x.country,
          latitude: x.latitude, longitude: x.longitude, timezone: x.timezone ?? "auto",
        })));
      } catch {
        return json(res, 200, [{ name: q || "モック地点", admin: null, country: "JP", latitude: 35.0, longitude: 135.0, timezone: "Asia/Tokyo" }]);
      }
    }

    // 静的ファイル
    let rel = path === "/" ? "index.html" : path === "/settings" ? "settings.html" : path.replace(/^\/static\//, "");
    rel = normalize(rel).replace(/^(\.\.[/\\])+/, "");
    const file = join(ROOT, rel);
    const body = await readFile(file);
    res.writeHead(200, { "Content-Type": MIME[extname(file)] ?? "application/octet-stream", "Cache-Control": "no-store" });
    res.end(body);
  } catch (e) {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end(`not found: ${path}`);
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`[mock] ${ROOT}`);
  console.log(`[mock] http://localhost:${PORT}  (設定画面: /settings)`);
});
