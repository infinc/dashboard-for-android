/*
 * 壁掛けダッシュボードの描画。
 *
 * 対象 WebView は Chrome 81 相当のため、ES2020 までの構文に留める
 * （?. と ?? は使えるが、.at() / replaceChildren() / structuredClone は使えない）。
 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var WDAY = ["日", "月", "火", "水", "木", "金", "土"];
  var SSID_MSG = {
    permission_required: "権限が必要",
    location_services_off: "位置情報サービスを ON に",
    unavailable: "取得できません"
  };

  var state = null;
  var configVersion = -1;
  var clockOffsetMs = 0;
  var rssiHistory = [];
  var cpuHistory = [];
  var CPU_HISTORY_MAX = 60;   // 2 秒間隔で約 2 分ぶん
  var CPU_MARKS = [20, 40, 60, 80, 100];
  var failures = 0;

  /*
   * アクセント色。
   *
   * CSS からは var(--accent) で使えるが、JS が組み立てる SVG では
   * 半透明の塗り（面のグラデーション、降水確率の棒）が要る。
   * 色を rgba() へ自由に展開できるよう r,g,b の数値でも持ち、
   * applyConfig() が設定の色で入れ替える。
   */
  var accentRgb = [77, 212, 255];

  function hexToRgb(hex) {
    var m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(String(hex || "").trim());
    if (!m) return [77, 212, 255];
    return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
  }

  /** アクセント色。alpha を省くと不透明。 */
  function accent(alpha) {
    var c = accentRgb;
    return alpha == null
      ? "rgb(" + c[0] + "," + c[1] + "," + c[2] + ")"
      : "rgba(" + c[0] + "," + c[1] + "," + c[2] + "," + alpha + ")";
  }

  /*
   * アクセント色を白へ寄せたもの。
   * 暗い面に小さな文字（降水確率の数値）を置くときに使う。
   * バイオレットのような暗めのアクセントでは、色そのままだと壁から読めない。
   */
  function accentLight(mix) {
    var c = accentRgb, t = mix == null ? 0.42 : mix;
    return "rgb(" +
      Math.round(c[0] + (255 - c[0]) * t) + "," +
      Math.round(c[1] + (255 - c[1]) * t) + "," +
      Math.round(c[2] + (255 - c[2]) * t) + ")";
  }

  /** 表示設定。state が来る前でも既定で描けるように空オブジェクトを返す。 */
  function disp() { return (state && state.config && state.config.display) || {}; }

  // ---------------------------------------------------------------- 小道具

  function pad(n) { return n < 10 ? "0" + n : String(n); }

  function now() { return new Date(Date.now() + clockOffsetMs); }

  function relative(ts) {
    if (!ts) return "未取得";
    var min = Math.round((Date.now() - ts) / 60000);
    if (min <= 0) return "たった今";
    if (min < 60) return min + " 分前";
    var h = Math.floor(min / 60);
    if (h < 24) return h + " 時間前";
    return Math.floor(h / 24) + " 日前";
  }

  function gb(bytes) { return (bytes / 1073741824).toFixed(1); }

  function text(el, value) { if (el && el.textContent !== value) el.textContent = value; }
  function html(el, value) { if (el && el.innerHTML !== value) el.innerHTML = value; }

  /*
   * スクロールするカードを、中身が変わったときだけ描き直す。
   *
   * 2 秒ごとのポーリングで毎回 innerHTML を入れ替えていたため、指でスクロールしている最中に
   * 位置が先頭へ戻っていた。外部由来のテキスト（震央地名・記事見出し）は XSS を避けるため
   * innerHTML を入れた後に textContent で流し込んでおり、生成文字列と innerHTML は
   * 常に一致しない。つまり html() の比較では「変化なし」を判定できない。
   * そこで元データから作った署名を要素に覚えさせ、署名が変わったときだけ描き直す。
   * 描き直すときも、見ていた位置はできるだけ保つ。
   */
  function keyed(el, signature, build, scrollSelector) {
    if (!el) return;
    if (el.getAttribute("data-sig") === signature) return;
    var before = scrollSelector ? el.querySelector(scrollSelector) : el;
    var top = before ? before.scrollTop : 0;
    var left = before ? before.scrollLeft : 0;
    build(el);
    el.setAttribute("data-sig", signature);
    var after = scrollSelector ? el.querySelector(scrollSelector) : el;
    if (after && top > 0) {
      after.scrollTop = Math.min(top, Math.max(0, after.scrollHeight - after.clientHeight));
    }
    if (after && left > 0) {
      after.scrollLeft = Math.min(left, Math.max(0, after.scrollWidth - after.clientWidth));
    }
  }

  /*
   * 「12 分前」のような相対時刻は時間とともに変わるが、これを署名に含めると
   * 1 分ごとにカードごと描き直すことになりスクロールが戻る。
   * 描画時は data-ts だけ埋めておき、表示文字列はここで毎回入れ替える。
   * textContent の書き換えではスクロール位置は動かない。
   */
  function refreshAges(root) {
    var nodes = (root || document).querySelectorAll("[data-ts]");
    for (var i = 0; i < nodes.length; i++) {
      text(nodes[i], relative(Number(nodes[i].getAttribute("data-ts")) || 0));
    }
  }

  function tempUnit() { return state && state.config.units.temperature === "f" ? "°F" : "°C"; }
  function windUnit() { return state && state.config.units.wind === "ms" ? "m/s" : "km/h"; }
  function fmtTemp(v) { return v == null ? "—" : Math.round(v) + "°"; }

  /** "2026-09-19T21:26:00+09:00" のような文字列から時刻部分だけ取り出す（Date を介さず確実に）。 */
  function hhmm(iso) {
    if (!iso) return "";
    var m = /T(\d{2}):(\d{2})/.exec(iso);
    return m ? m[1] + ":" + m[2] : "";
  }
  function mmdd(iso) {
    if (!iso) return "";
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
    return m ? Number(m[2]) + "/" + Number(m[3]) : "";
  }
  function wdayOf(dateStr) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(dateStr || "");
    if (!m) return "";
    return WDAY[new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getDay()];
  }

  // ---------------------------------------------------------------- 時計

  /*
   * 表示中の時刻がどのタイムゾーンのものかを UTC からのずれで示す。
   * 端末の設定から求めるので、夏時間のある地域でもその日の実際のずれになる。
   */
  function utcOffset(d) {
    var min = -d.getTimezoneOffset();
    var abs = Math.abs(min);
    var rest = abs % 60;
    return "UTC" + (min < 0 ? "-" : "+") + Math.floor(abs / 60) + (rest ? ":" + pad(rest) : "");
  }

  function renderClock() {
    var d = now();
    var use24 = !state || state.config.units.clock24h;
    var h = d.getHours();
    var suffix = "";
    if (!use24) {
      suffix = h < 12 ? "AM" : "PM";
      h = h % 12; if (h === 0) h = 12;
    }
    var showSec = !state || state.config.units.showSeconds;
    html($("clockTime"),
      '<span>' + (use24 ? pad(h) : h) + ":" + pad(d.getMinutes()) + '</span>' +
      (showSec ? '<span class="clock-sec num">' + pad(d.getSeconds()) + "</span>" : "") +
      (suffix ? '<span class="clock-sec">' + suffix + "</span>" : ""));
    var wd = " (" + WDAY[d.getDay()] + ")";
    text($("clockDate"), disp().clockDateFormat === "slash"
      ? d.getFullYear() + "/" + pad(d.getMonth() + 1) + "/" + pad(d.getDate()) + wd
      : d.getFullYear() + "年" + (d.getMonth() + 1) + "月" + d.getDate() + "日" + wd);
    text($("clockTz"), utcOffset(d));

    // 天気の地点が端末と別タイムゾーンのときだけ、現地時刻を併記して混乱を防ぐ
    var sub = "";
    if (state && state.weather && state.weather.timezone && state.weather.timezone !== state.deviceTimezone) {
      try {
        var local = new Intl.DateTimeFormat("ja-JP", {
          timeZone: state.weather.timezone, hour: "2-digit", minute: "2-digit", hour12: false
        }).format(d);
        sub = state.weather.placeName + " は " + local;
      } catch (e) { sub = ""; }
    }
    text($("clockSub"), sub);
  }

  // ---------------------------------------------------------------- メモ

  /** 中継が 1 件しか返さない旧応答でも、常に配列として扱えるようにする。 */
  function memoItems(memo) {
    if (!memo) return [];
    if (memo.items && memo.items.length) return memo.items;
    if (memo.text) {
      return [{ id: "legacy", text: memo.text, senderName: memo.senderName, receivedAt: memo.receivedAt }];
    }
    return [];
  }

  function renderMemo(memo) {
    var body = $("memoBody");
    var items = memoItems(memo);
    var enabled = !state || state.config.memo.enabled;

    var sig = [enabled ? "on" : "off", (memo && memo.lastError) || ""];
    for (var i = 0; i < items.length; i++) sig.push(items[i].id + "@" + items[i].receivedAt);

    keyed(body, sig.join("|"), function (el) {
      if (items.length) {
        // 1 件だけなら従来どおり大きく見せる。複数のときは一覧にしてスクロールで読む。
        var out = '<div class="memo-list' + (items.length === 1 ? " single" : "") + '">';
        for (var j = 0; j < items.length; j++) {
          out += '<div class="memo-item"><div class="memo-text"></div>' +
            '<div class="memo-meta"><span class="memo-who"></span>' +
            '<span class="memo-age" data-ts="' + (items[j].receivedAt || 0) + '"></span></div></div>';
        }
        html(el, out + "</div>");
        var nodes = el.querySelectorAll(".memo-item");
        for (var k = 0; k < nodes.length; k++) {
          // LINE から来た本文と送信者名は外部由来なので textContent で入れる
          nodes[k].querySelector(".memo-text").textContent = items[k].text;
          nodes[k].querySelector(".memo-who").textContent = items[k].senderName || "";
        }
      } else if (!enabled) {
        html(el, '<div class="memo-empty">LINE 連携は未設定です。<br>設定画面から中継先と端末トークンを登録してください。</div>');
      } else if (memo && memo.lastError) {
        html(el, '<div class="memo-empty bad"></div>');
        el.firstChild.textContent = "メモを取得できません: " + memo.lastError;
      } else {
        html(el, '<div class="memo-empty">LINE でメッセージを送ると、ここに表示されます。<br>「/clear」と送ると全部消えます。</div>');
      }
    });

    // 件数は常に出す。0 件や 1 件のときだけ消えると、
    // 「表示されていないのか、そもそも無いのか」が壁から見て分からなくなる。
    text($("memoNote"), enabled ? "(" + items.length + " 件)" : "");
  }

  // ---------------------------------------------------------------- 天気

  var COMPASS = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                 "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];

  /** 気象の風向は「風が吹いてくる方角」。16 方位の略号に直す。 */
  function windDir(deg) {
    if (deg == null) return "";
    var d = ((deg % 360) + 360) % 360;
    return COMPASS[Math.round(d / 22.5) % 16];
  }

  function fmtUv(v) {
    if (v == null) return "—";
    // 早朝・夜間は 0.2 のような値になるので、1 未満だけ小数を残す
    return v < 1 ? v.toFixed(1) : String(Math.round(v));
  }

  /** UV 指数の WHO 区分。数値だけでは強さが判断できないので色で補う。 */
  function uvClass(v) {
    if (v == null) return "";
    if (v >= 8) return "bad";
    if (v >= 3) return "warn";
    return "good";
  }

  function fmtVisibility(m) {
    if (m == null) return "—";
    var km = m / 1000;
    return (km >= 10 ? Math.round(km) : km.toFixed(1)) + " km";
  }

  /**
   * European AQI の区分。0 が最良で、100 を超えると「非常に悪い」。
   * 数値だけ出しても良し悪しが伝わらないため、区分名を併記する。
   */
  function aqiLabel(v) {
    if (v == null) return { text: "—", cls: "" };
    if (v <= 20) return { text: v + " 良い", cls: "good" };
    if (v <= 40) return { text: v + " やや良", cls: "good" };
    if (v <= 60) return { text: v + " 普通", cls: "" };
    if (v <= 80) return { text: v + " 悪い", cls: "warn" };
    if (v <= 100) return { text: v + " かなり悪", cls: "bad" };
    return { text: v + " 非常に悪", cls: "bad" };
  }

  /**
   * PM2.5 の目安。環境省の環境基準は日平均 35μg/m³ 以下、
   * 注意喚起の暫定指針は日平均 70μg/m³。段階はこれに寄せる。
   */
  function pm25Class(v) {
    if (v == null) return "";
    if (v >= 70) return "bad";
    if (v >= 35) return "warn";
    if (v >= 15) return "";
    return "good";
  }

  function wxCell(label, value, cls) {
    return '<div><span>' + label + '</span><b class="num ' + (cls || "") + '">' + value + "</b></div>";
  }

  /*
   * 天気カードに出せる項目。
   * キーは Kotlin 側の DEFAULT_WEATHER_FIELDS と一致させること
   * （設定画面はこのキーで保存し、ここが描く）。
   */
  var WX_FIELDS = {
    apparent: function (c) { return wxCell("体感", fmtTemp(c.apparentTemperature)); },
    pm25: function (c) {
      return wxCell("PM2.5", c.pm25 == null ? "—" : c.pm25.toFixed(1) + " μg/m³", pm25Class(c.pm25));
    },
    pop: function (c) {
      return wxCell("降水確率", c.precipitationProbability == null ? "—" : c.precipitationProbability + "%");
    },
    rain: function (c) {
      return wxCell("降水量", c.precipitation == null ? "—" : c.precipitation.toFixed(1) + " mm",
        c.precipitation ? "" : "dim");
    },
    humidity: function (c) { return wxCell("湿度", c.humidity == null ? "—" : c.humidity + "%"); },
    wind: function (c) {
      var v = c.windSpeed == null
        ? "—"
        : Math.round(c.windSpeed) + " " + windUnit() + (windDir(c.windDirection) ? " " + windDir(c.windDirection) : "");
      return wxCell("風", v);
    },
    uv: function (c) { return wxCell("UV", fmtUv(c.uvIndex), uvClass(c.uvIndex)); },
    aqi: function (c) { var a = aqiLabel(c.aqi); return wxCell("AQI", a.text, a.cls); },
    visibility: function (c) { return wxCell("視界", fmtVisibility(c.visibilityMeters)); }
  };

  var WX_FIELD_ORDER = ["apparent", "pm25", "pop", "rain", "humidity", "wind", "uv", "aqi", "visibility"];

  function renderWeather(w) {
    if (!w || !w.available) {
      html($("wxBody"), '<div class="memo-empty">天気を取得できていません。</div>');
      return;
    }
    var c = w.current;

    /*
     * 出す項目は設定で選ぶ。3 列で折り返すので、9 項目なら 3 行にちょうど収まる。
     * 未設定（古い config）のときは従来どおり全項目。
     */
    var want = disp().weatherFields || WX_FIELD_ORDER;
    var cells = "";
    for (var i = 0; i < want.length; i++) {
      var build = WX_FIELDS[want[i]];
      if (build) cells += build(c);
    }

    html($("wxBody"),
      '<div class="wx-now">' + WxIcons.svg(c.weatherCode, c.isDay) +
      '<div><div class="wx-temp num">' + fmtTemp(c.temperature) + "</div>" +
      '<div class="wx-label">' + WxIcons.label(c.weatherCode) + "</div></div>" +
      "</div>" +
      // 他と同じ「左＝名前／右＝値」で並べる。
      // 3 列。2 列だと 9 項目で 5 行になり、最終行がカードからはみ出す。
      '<div class="wx-grid">' + cells + "</div>");
    text($("wxNote"), (w.placeName || "") + " ・ " + relative(w.fetchedAt));
  }

  // ---------------------------------------------------------------- 時間別予報

  function renderHourly(w) {
    var hours = (w && w.hourly) || [];
    var el = $("hourlyBody");
    if (!hours.length) { html(el, '<div class="memo-empty">予報データがありません。</div>'); return; }

    // viewBox をカードの実寸に合わせる。
    // 以前は固定 viewBox + preserveAspectRatio="none" で描いていたため、
    // 縦横が別々に引き伸ばされて文字まで潰れ、指定より小さく見えていた。
    // 実寸に合わせれば 1 単位 = 1px になり、フォントサイズがそのまま効く。
    var W = el.clientWidth || 700;
    var H = el.clientHeight || 150;
    // 上端に降水確率専用の帯を確保し、気温のラベルと絶対に重ならないようにする
    var padX = 38, padTop = 36, padBottom = 32, pctY = 13;

    /*
     * 何を描くか。"both"（既定）/ "temp" = 気温だけ / "precip" = 降水確率だけ。
     * 降水確率だけのときは棒を縦いっぱいまで使う。
     * 折れ線と重ねる前提の 55% のままだと、上半分が空いたまま読みにくい。
     */
    var mode = disp().hourlyMode || "both";
    var wantTemp = mode !== "precip";
    var wantPop = mode !== "temp";
    var barScale = wantTemp ? 0.55 : 1;

    html($("hourlyLegend"),
      (wantTemp ? '<i class="sw sw-line"></i><span>気温</span>' : "") +
      (wantTemp && wantPop ? '<i class="sep">/</i>' : "") +
      (wantPop ? '<i class="sw sw-bar"></i><span>降水確率</span>' : ""));

    var temps = [];
    for (var i = 0; i < hours.length; i++) if (hours[i].temperature != null) temps.push(hours[i].temperature);
    if (wantTemp && !temps.length) { html(el, '<div class="memo-empty">予報データがありません。</div>'); return; }
    var min = temps.length ? Math.min.apply(null, temps) : 0;
    var max = temps.length ? Math.max.apply(null, temps) : 1;
    if (max - min < 1) { max = min + 1; }
    var step = (W - padX - 26) / Math.max(1, hours.length - 1);

    /*
     * 降水確率のラベルを何点おきに出すか。
     * "100%" は 12.5px でおよそ 30px を要するので、1 点あたりの幅がそれに満たないときは
     * 1 つおきにする。カードを狭めたときに数字が数珠つなぎになって読めなくなるため。
     */
    var pctEvery = step < 40 ? 2 : 1;

    function x(i) { return padX + step * i; }
    function y(t) { return padTop + (H - padTop - padBottom) * (1 - (t - min) / (max - min)); }

    // 何のグラフか分かるよう、気温の上限・下限をガイド線と数値で示す
    var axis =
      '<line x1="' + padX + '" y1="' + padTop + '" x2="' + (W - 6) + '" y2="' + padTop +
        '" stroke="rgba(255,255,255,0.055)" stroke-width="1"/>' +
      '<line x1="' + padX + '" y1="' + (H - padBottom) + '" x2="' + (W - 6) + '" y2="' + (H - padBottom) +
        '" stroke="rgba(255,255,255,0.055)" stroke-width="1"/>' +
      '<text x="2" y="' + (padTop + 4) + '" font-size="11" fill="#5B6774">' +
        (wantTemp ? Math.round(max) + "°" : "100%") + '</text>' +
      '<text x="2" y="' + (H - padBottom + 4) + '" font-size="11" fill="#5B6774">' +
        (wantTemp ? Math.round(min) + "°" : "0%") + '</text>';

    var line = "", area = "", bars = "", labels = "";
    for (var j = 0; j < hours.length; j++) {
      var hv = hours[j];
      if (wantTemp && hv.temperature != null) {
        line += (line ? " L" : "M") + x(j).toFixed(1) + " " + y(hv.temperature).toFixed(1);
      }
      var p = hv.precipitationProbability || 0;
      if (wantPop && p > 0) {
        var bh = (H - padBottom - padTop) * (p / 100) * barScale;
        bars += '<rect x="' + (x(j) - 7).toFixed(1) + '" y="' + (H - padBottom - bh).toFixed(1) +
          '" width="14" height="' + bh.toFixed(1) + '" rx="3" fill="' + accent(0.2) + '"/>';
        // 無視できない降水確率だけ数値を出す。全部出すと読めなくなる。
        // 降水確率は折れ線の上下動と無関係な固定行に置く。
        // 棒の上や下に置くと、気温が低い時間帯で気温ラベルと必ず重なる。
        if (p >= 30 && j % pctEvery === 0) {
          bars += '<text x="' + x(j).toFixed(1) + '" y="' + pctY +
            '" text-anchor="middle" font-size="12.5" font-weight="600" fill="' + accentLight() + '">' +
            p + '%</text>';
        }
      }
      if (j % 2 === 0) {
        labels += '<text x="' + x(j).toFixed(1) + '" y="' + (H - 10) +
          '" text-anchor="middle" font-size="12" fill="#7C8B9B">' + hhmm(hv.time) + "</text>";
        if (wantTemp && hv.temperature != null) {
          labels += '<text x="' + x(j).toFixed(1) + '" y="' + (y(hv.temperature) - 8).toFixed(1) +
            '" text-anchor="middle" font-size="12.5" fill="#E8EEF5" font-weight="650">' +
            Math.round(hv.temperature) + "°</text>";
        }
      }
    }
    if (line) {
      area = line + " L" + x(hours.length - 1).toFixed(1) + " " + (H - padBottom) + " L" + padX + " " + (H - padBottom) + " Z";
    }

    html(el,
      '<svg class="chart" viewBox="0 0 ' + W + " " + H + '">' +
      '<defs><linearGradient id="hg" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0%" stop-color="' + accent(0.26) + '"/>' +
      '<stop offset="100%" stop-color="' + accent(0) + '"/>' +
      "</linearGradient></defs>" +
      axis + bars +
      (area ? '<path d="' + area + '" fill="url(#hg)"/>' : "") +
      (line ? '<path d="' + line + '" fill="none" stroke="' + accent() +
        '" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round"/>' : "") +
      labels + "</svg>");
  }

  // ---------------------------------------------------------------- 週間予報

  function renderDaily(w) {
    var days = (w && w.daily) || [];
    var el = $("dailyBody");
    if (!days.length) {
      keyed(el, "empty", function (node) {
        html(node, '<div class="memo-empty">週間予報がありません。</div>');
      });
      return;
    }

    /*
     * 中身が変わったときだけ描き直す。
     * このカードは天気アイコンの SVG を含み、innerHTML に入れると
     * 自己終了タグなどが正規化されて元の文字列と一致しなくなる。
     * つまり html() の比較では毎回「変化あり」になり、2 秒ごとに作り直されて
     * 横スクロールが左端へ戻っていた。
     */
    var sig = [];
    for (var k = 0; k < days.length; k++) {
      var dd = days[k];
      sig.push([dd.date, dd.tempMax, dd.tempMin, dd.weatherCode,
                dd.precipitationSum, dd.precipitationProbabilityMax].join(","));
    }
    keyed(el, sig.join("|"), function (node) { buildDaily(node, days); }, ".daily-days");
  }

  function buildDaily(el, days) {

    var lows = [], highs = [];
    for (var i = 0; i < days.length; i++) {
      if (days[i].tempMin != null) lows.push(days[i].tempMin);
      if (days[i].tempMax != null) highs.push(days[i].tempMax);
    }
    var gmin = lows.length ? Math.min.apply(null, lows) : 0;
    var gmax = highs.length ? Math.max.apply(null, highs) : 1;
    if (gmax - gmin < 1) gmax = gmin + 1;

    // 列幅は CSS 側で固定する。半分幅のカードに 7 日を詰めると読めないので、
    // はみ出したぶんは横スクロールで見る。
    var out = '<div class="daily"><div class="daily-days">';
    for (var d = 0; d < days.length; d++) {
      var day = days[d];
      var lo = day.tempMin, hi = day.tempMax;
      var left = lo == null ? 0 : ((lo - gmin) / (gmax - gmin)) * 100;
      var width = (lo == null || hi == null) ? 0 : ((hi - lo) / (gmax - gmin)) * 100;
      var wd = wdayOf(day.date);
      var color = (d === 0) ? "var(--accent)" : (wd === "日" ? "var(--red)" : wd === "土" ? "var(--violet)" : "var(--text-2)");

      var rain;
      if (day.precipitationSum != null && day.precipitationSum > 0) {
        rain = "<b>" + day.precipitationSum.toFixed(1) + "</b>mm";
      } else {
        rain = '<span class="dim">0mm</span>';
      }
      if (day.precipitationProbabilityMax != null) {
        rain += " <span>" + day.precipitationProbabilityMax + "%</span>";
      }

      out += '<div class="day">' +
        '<div class="day-name" style="color:' + color + '">' + (d === 0 ? "今日" : mmdd(day.date) + " " + wd) + "</div>" +
        '<div class="day-icon">' + WxIcons.svg(day.weatherCode, true) + "</div>" +
        '<div class="day-temps num"><b>' + fmtTemp(hi) + '</b> <span class="dim">' + fmtTemp(lo) + "</span></div>" +
        '<div class="day-bar"><div class="fill" style="left:' + left.toFixed(1) + "%;width:" +
        Math.max(width, 4).toFixed(1) + '%"></div></div>' +
        '<div class="day-rain num">' + rain + "</div>" +
        "</div>";
    }
    // 棒が何を表すかを数値で示す。凡例だけでは目盛りの基準が分からないため。
    out += "</div>" +
      '<div class="daily-scale"><span class="num">' + Math.round(gmin) + "°</span>" +
      '<span class="mid"></span>' +
      '<span class="num">' + Math.round(gmax) + "°</span></div></div>";
    html(el, out);
  }

  // ---------------------------------------------------------------- Wi-Fi

  function renderWifi(wifi, stats) {
    rssiHistory.push(wifi.rssiDbm == null ? null : wifi.rssiDbm);
    if (rssiHistory.length > 60) rssiHistory.shift();

    var bars = "";
    for (var i = 0; i < 4; i++) {
      bars += '<i class="' + (i < wifi.signalLevel ? "on" : "") + '" style="height:' + (5 + i * 4) + 'px"></i>';
    }

    var ssid = wifi.ssidStatus === "ok" && wifi.ssid
      ? wifi.ssid
      : (SSID_MSG[wifi.ssidStatus] || "—");
    var ssidClass = wifi.ssidStatus === "ok" ? "" : "warn";

    html($("wifiMain"),
      '<div class="wifi-main"><div class="bars">' + bars + "</div>" + speeds(wifi) + "</div>");
    html($("wifiRows"),
      '<div class="wifi-rows">' +
      '<div class="wifi-row"><span>通信量</span><b class="num">' + trafficText(stats) + "</b></div>" +
      '<div class="wifi-row"><span>SSID</span><b class="' + ssidClass + '"></b></div>' +
      '<div class="wifi-row"><span>強度</span><b class="num">' + strength(wifi) + "</b></div>" +
      "</div>");
    html($("wifiSpark"), sparkline());
    // IP と帯域は動かない情報なのでヘッダ側に逃がし、本文の行数を通信量に回す
    text($("wifiNote"), [wifi.ipAddress, wifi.band].filter(Boolean).join(" ・ "));
    // SSID はユーザー入力由来の文字列なので innerHTML に混ぜず textContent で入れる
    var rows = $("wifiRows").querySelectorAll(".wifi-row b");
    if (rows.length > 1) rows[1].textContent = ssid;
  }

  /*
   * 下り（受信）と上り（送信）のリンク速度。
   *
   * ここに出るのは Wi-Fi が親機とネゴシエートしたリンクレートであって、
   * インターネット回線の実効速度ではない（本アプリは通信を発生させないため測れない）。
   *
   * 方向別の値は Android 10 (API 29) 以降の API だが、実際に返すかどうかは端末の
   * Wi-Fi ドライバ次第で、上りだけ返して下りは「不明」になる機種がある
   * （Lenovo TB-X306F / Android 10 で確認）。片方が欠けたまま「—」を並べても
   * 読む側に何も伝わらないので、両方そろったときだけ 2 行に分ける。
   */
  function speeds(wifi) {
    var rx = wifi.rxLinkSpeedMbps, tx = wifi.txLinkSpeedMbps;
    if (rx == null || tx == null) {
      // 行が 1 つなら高さが余るので、そのぶん数字を大きく見せる。
      var one = rx != null ? rx : (tx != null ? tx : wifi.linkSpeedMbps);
      return '<div class="wifi-speeds single">' + speedCell("", "", one) + "</div>";
    }
    return '<div class="wifi-speeds">' +
      speedCell("↓", "下り", rx) +
      speedCell("↑", "上り", tx) + "</div>";
  }

  function speedCell(arrow, label, value) {
    return '<div class="wifi-sp"><span class="wifi-dir">' + arrow +
      (label ? "<em>" + label + "</em>" : "") + "</span>" +
      '<b class="num">' + (value == null ? "—" : value) + "</b>" +
      '<small class="wifi-unit">Mbps</small></div>';
  }

  /*
   * いま実際に流れている通信量。リンク速度（親機との取り決め）と違って実測値。
   *
   * 下りと上りで桁が大きく離れることが多いので、単位は大きいほうに合わせて
   * 両方そろえる。片方だけ kbps、片方 Mbps だと並べたときに読み違える。
   */
  function trafficText(stats) {
    if (!stats) return "—";
    var rx = stats.rxBitsPerSec, tx = stats.txBitsPerSec;
    // 起動直後は前回値が無く差分を取れない。
    if (rx == null || tx == null) return "計測中…";
    var mbps = Math.max(rx, tx) >= 1000000;
    var div = mbps ? 1000000 : 1000;
    return "↓" + rate(rx / div) + " ↑" + rate(tx / div) + " " + (mbps ? "Mbps" : "kbps");
  }

  function rate(v) {
    return v >= 10 ? String(Math.round(v)) : String(Math.round(v * 10) / 10);
  }

  /*
   * dBm は数字だけでは強いのか弱いのか分からないので、言葉のラベルを添える。
   * 段階は端末が返す signalLevel（0..4）に合わせる。RSSI の閾値を自前で決めると
   * 左のバー（同じ signalLevel で点灯する）と食い違うことがあるため。
   */
  var LEVEL_LABEL = ["非常に弱い", "弱い", "普通", "強い", "非常に強い"];
  var LEVEL_CLASS = ["bad", "bad", "warn", "good", "good"];

  function strength(wifi) {
    if (wifi.rssiDbm == null) return "—";
    var i = Math.max(0, Math.min(4, wifi.signalLevel || 0));
    return wifi.rssiDbm + " dBm <span class=\"wifi-level " + LEVEL_CLASS[i] + '">(' +
      LEVEL_LABEL[i] + ")</span>";
  }

  function sparkline() {
    var vals = [];
    for (var i = 0; i < rssiHistory.length; i++) if (rssiHistory[i] != null) vals.push(rssiHistory[i]);
    if (vals.length < 3) return "";
    var min = -90, max = -30, W = 200, H = 26, step = W / (rssiHistory.length - 1), path = "";
    for (var j = 0; j < rssiHistory.length; j++) {
      var v = rssiHistory[j];
      if (v == null) continue;
      var y = H * (1 - (Math.max(min, Math.min(max, v)) - min) / (max - min));
      path += (path ? " L" : "M") + (j * step).toFixed(1) + " " + y.toFixed(1);
    }
    return '<svg viewBox="0 0 ' + W + " " + H + '" preserveAspectRatio="none" style="width:100%;height:16px;margin-top:3px;display:block">' +
      '<path d="' + path + '" fill="none" stroke="' + accent(0.55) + '" stroke-width="1.6"/></svg>';
  }

  // ---------------------------------------------------------------- 端末状態

  function meter(label, value, pct, cls) {
    return '<div class="meter"><div class="meter-top"><span>' + label + '</span><b class="num">' + value + "</b></div>" +
      '<div class="meter-bar"><div class="meter-fill ' + (cls || "") + '" style="width:' + Math.max(0, Math.min(100, pct)).toFixed(1) + '%"></div></div></div>';
  }

  /*
   * CPU 使用率の推移。
   * 2 秒ごとの値を貯めて折れ線にする。瞬間値だけでは、
   * 「いま重いのか、ずっと重いのか」が壁から見て区別できない。
   */
  function cpuChart() {
    if (cpuHistory.length < 2) {
      return '<div class="cpu-chart-empty">計測中…</div>';
    }
    // 履歴が溜まるまで右端に寄せると、最初の 2 分間ほぼ空白のグラフになる。
    // 常に幅いっぱいへ引き伸ばし、時間窓のほうが伸びていく形にする。
    var W = 200, H = 100, step = W / (cpuHistory.length - 1);
    var line = "";
    for (var i = 0; i < cpuHistory.length; i++) {
      var y = H - (H - 2) * (cpuHistory[i] / 100);
      line += (line ? " L" : "M") + (i * step).toFixed(1) + " " + y.toFixed(1);
    }
    var area = line + " L" + W + " " + H + " L0 " + H + " Z";

    // 20/40/60/80/100% に薄い横線。線だけでは高さの絶対値が読めないため。
    var grid = "";
    for (var m = 0; m < CPU_MARKS.length; m++) {
      var gy = (H - (H - 2) * (CPU_MARKS[m] / 100)).toFixed(1);
      grid += '<line x1="0" y1="' + gy + '" x2="' + W + '" y2="' + gy +
        '" stroke="rgba(255,255,255,0.10)" stroke-width="1" vector-effect="non-scaling-stroke"/>';
    }
    return '<svg class="cpu-chart" viewBox="0 0 ' + W + " " + H + '" preserveAspectRatio="none">' +
      '<defs><linearGradient id="cpug" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0%" stop-color="' + accent(0.35) + '"/>' +
      '<stop offset="100%" stop-color="' + accent(0) + '"/></linearGradient></defs>' +
      grid +
      '<path d="' + area + '" fill="url(#cpug)"/>' +
      '<path d="' + line + '" fill="none" stroke="var(--accent)" stroke-width="2" ' +
      'stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/></svg>';
  }

  /** グラフ右脇の目盛り。SVG の外に置いて、引き伸ばしの影響を受けないようにする。 */
  function cpuScale() {
    var out = '<div class="cpu-scale">';
    for (var i = 0; i < CPU_MARKS.length; i++) {
      // グラフ内の y と同じ式で位置を合わせる（上端 2% ぶんの余白込み）
      var topPct = (100 - 98 * (CPU_MARKS[i] / 100)).toFixed(1);
      out += '<span style="top:' + topPct + '%">' + CPU_MARKS[i] + "%</span>";
    }
    return out + "</div>";
  }

  /**
   * 温度のメーター。
   * 端末が読めるのはバッテリー温度だけ（thermal_zone はアプリから読めない）。
   * 0-60℃ を全幅に割り当てる。常温付近でバーが動かないと意味が無いため。
   */
  function tempMeter(c) {
    var cls = c >= 45 ? "bad" : c >= 40 ? "warn" : c >= 35 ? "" : "good";
    return meter("温度（バッテリー）", c.toFixed(1) + " ℃", (c / 60) * 100, cls);
  }

  /*
   * 充電コードの抜き差しを知らせる。
   *
   * 壁掛けで挿しっぱなしにする運用なので、抜けたことに気づけないと数時間後に落ちる。
   * 音はタイマー（880Hz の正弦波を 3 連打）とも防災通知（三角波で高→低）とも
   * 取り違えないよう、正弦波の 2 音で上がる／下がるを対にしている。
   * 起動直後は前の状態が無いので鳴らさない。
   */
  var lastCharging = null;

  function checkChargingChange(s) {
    var now = !!s.charging;
    if (lastCharging === null) { lastCharging = now; return; }
    if (lastCharging === now) return;
    lastCharging = now;
    // 覚えの更新は音を切っていても続ける。次に入れたときに鳴らせなくなるため。
    if (notices().chargingSound === false) return;
    if (now) {
      // 挿さった: 低→高
      playCue([[523.3, 0, 0.14], [784.0, 0.13, 0.26]]);
    } else {
      // 抜けた: 高→低
      playCue([[784.0, 0, 0.14], [523.3, 0.13, 0.30]]);
    }
  }

  function renderStats(s) {
    checkChargingChange(s);
    if (s.cpuPercent != null) {
      cpuHistory.push(s.cpuPercent);
      if (cpuHistory.length > CPU_HISTORY_MAX) cpuHistory.shift();
    }

    var out = '<div class="stats-split">';
    out += '<div class="cpu"><div class="meter-top"><span>CPU 使用率</span>' +
      '<b class="num">' + (s.cpuPercent == null ? "—" : s.cpuPercent + "%") + "</b></div>" +
      '<div class="cpu-chart-wrap">' + cpuChart() + cpuScale() + "</div></div>";

    // 右半分に残りの値を縦に積む
    var meters = "";
    if (s.batteryPercent != null) {
      var cls = s.charging ? "good" : s.batteryPercent < 20 ? "bad" : s.batteryPercent < 40 ? "warn" : "";
      // 充電が外れていることは見出しの中で伝える。
      // 別行の警告にするとカードの高さを超えて下へはみ出す。
      var label = s.charging
        ? "バッテリー（充電中）"
        : 'バッテリー<span class="bad">（放電中）</span>';
      meters += meter(label, s.batteryPercent + "%", s.batteryPercent, cls);
    }
    if (s.batteryTemperatureC != null) {
      meters += tempMeter(s.batteryTemperatureC);
    }
    if (s.storageTotalBytes > 0) {
      var used = s.storageTotalBytes - s.storageFreeBytes;
      meters += meter("ストレージ", gb(s.storageFreeBytes) + " GB 空き",
        (used / s.storageTotalBytes) * 100, (s.storageFreeBytes / s.storageTotalBytes) < 0.1 ? "warn" : "");
    }
    if (s.memoryTotalBytes > 0) {
      var mused = s.memoryTotalBytes - s.memoryAvailableBytes;
      meters += meter("メモリ", gb(s.memoryAvailableBytes) + " GB 空き", (mused / s.memoryTotalBytes) * 100, "");
    }
    out += '<div class="meters">' + meters + "</div></div>";
    html($("statsBody"), out);
  }

  // ---------------------------------------------------------------- 防災

  /** 震度の強さで色を変える。数字だけでは壁から見て強弱が伝わらない。 */
  function shindoClass(v) {
    var n = parseFloat(String(v || "").charAt(0));
    if (isNaN(n)) return "";
    // クラス名は lv を付ける。s3 / s5 はカードの列幅ユーティリティ
    // （.s3 { grid-column: span 3 }）と衝突し、震度バッジが列をまたいで伸びる。
    if (n >= 5) return "lv5";
    if (n >= 3) return "lv3";
    return "";
  }

  /**
   * 台風 1 つを 3 つの行に組み立てる。
   * 気象庁が出していない項目（小さい台風には「大きさ」が付かない等）は詰めて出す。
   */
  function typhoonText(t) {
    var course = "";
    if (t.course) course = t.course + "へ" + (t.speedKmh ? " " + t.speedKmh + " km/h" : "");
    return {
      head: [t.number, t.name].filter(Boolean).join(" ") || "台風",
      kind: [t.scale, t.intensity].filter(Boolean).join("・"),
      line1: [t.location, t.pressureHpa ? t.pressureHpa + " hPa" : "", course]
        .filter(Boolean).join(" ・ "),
      line2: [
        t.maxWindMps ? "最大風速 " + t.maxWindMps + " m/s" : "",
        t.gustMps ? "瞬間 " + t.gustMps + " m/s" : ""
      ].filter(Boolean).join(" ／ ")
    };
  }

  // ------------------------------------------------------------ 更新の通知

  /*
   * 警報・注意報・津波・台風・噴火が新しく出たり切り替わったりしたときだけ知らせる。
   *
   * 見比べるのは「いま何が出ているか」であって「いつ発表されたか」ではない。
   * 気象庁は内容が変わらなくても発表時刻を打ち直すことがあり、時刻を含めると
   * 同じ注意報で何度も鳴ってしまう。
   *
   * 台風は位置・気圧・進路が数時間ごとに更新されるが、そこでは鳴らさない。
   * 夜通しつけっぱなしの壁掛けで 3 時間おきに音が鳴るのは通知として成立しない。
   * 号数・名前・大きさ・強さが変わったときだけ「切り替わった」とみなす。
   */
  var ALERT_KEY = "walldash.disasterSeen";
  // 種類の並びはそのまま通知の優先順位。津波が出ていれば津波を見出しにする。
  var ALERT_LABELS = {
    tsunami: "津波",
    warning: "警報・注意報",
    typhoon: "台風",
    volcano: "噴火"
  };
  var alertSeen = null;
  var spotifyLast = null;      // 直近に受け取った再生状態。経過時間の補間に使う。
  var toastTimer = null;
  var audioCloseTimer = null;

  function disasterMarks(d) {
    var i;
    var areas = d.activeAreas || [];
    var warn = [d.headline || ""];
    for (i = 0; i < areas.length; i++) {
      warn.push(areas[i].code + ":" + (areas[i].kinds || []).join(","));
    }

    var ts = d.tsunami || [], tsMark = [];
    for (i = 0; i < ts.length; i++) tsMark.push(ts[i].title || "津波情報");

    var tp = d.typhoons || [], tpMark = [];
    for (i = 0; i < tp.length; i++) {
      tpMark.push([tp[i].number, tp[i].name, tp[i].scale, tp[i].intensity].join("/"));
    }

    var vc = d.volcanoes || [], vcMark = [];
    for (i = 0; i < vc.length; i++) vcMark.push(vc[i].name + "/" + vc[i].level);

    return {
      warning: warn.join("|"),
      tsunami: tsMark.join("|"),
      typhoon: tpMark.join("|"),
      volcano: vcMark.join("|")
    };
  }

  function loadSeen() {
    try {
      var raw = localStorage.getItem(ALERT_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function saveSeen(marks) {
    try { localStorage.setItem(ALERT_KEY, JSON.stringify(marks)); } catch (e) { /* 無視 */ }
  }

  function checkDisasterAlert(d) {
    var marks = disasterMarks(d);
    if (alertSeen === null) alertSeen = loadSeen();
    if (alertSeen === null) {
      // 初めて動かしたときは比べる相手がいない。基準だけ作って鳴らさない。
      alertSeen = marks;
      saveSeen(marks);
      return;
    }

    var changed = [];
    for (var key in ALERT_LABELS) {
      if (ALERT_LABELS.hasOwnProperty(key) && marks[key] !== alertSeen[key]) changed.push(key);
    }
    if (!changed.length) return;

    alertSeen = marks;
    saveSeen(marks);

    var labels = [];
    for (var i = 0; i < changed.length; i++) labels.push(ALERT_LABELS[changed[i]]);
    // 音が先。バナーの表示は Activity 呼び出し（明るさ戻し）を挟むので、
    // 後ろに置くと鳴り始めが遅れる。
    chime();
    showToast(labels.join(" ・ "), alertDetail(d, changed[0]), changed[0] === "tsunami");
  }

  /** 通知に出す 1 行。解除されて空になった場合もそれと分かる文にする。 */
  function alertDetail(d, key) {
    var list, i, out;
    if (key === "tsunami") {
      list = d.tsunami || [];
      if (!list.length) return "津波情報は解除されました";
      out = [];
      for (i = 0; i < list.length; i++) out.push(list[i].title || "津波情報");
      return out.join(" ・ ");
    }
    if (key === "typhoon") {
      list = d.typhoons || [];
      if (!list.length) return "台風の情報はなくなりました";
      var t = list[0];
      var kind = [t.scale, t.intensity].filter(Boolean).join("・");
      return [t.number, t.name].filter(Boolean).join(" ") + (kind ? "（" + kind + "）" : "");
    }
    if (key === "volcano") {
      list = d.volcanoes || [];
      if (!list.length) return "噴火警報は出ていません";
      return list[0].name + " " + list[0].level;
    }
    if (d.headline) return d.headline;
    list = d.activeAreas || [];
    if (!list.length) return "警報・注意報は解除されました";
    return list[0].name + " " + (list[0].kinds || []).join("・");
  }

  /*
   * Activity 側の機能を呼ぶ。PC のブラウザで開発しているときは解決できず何も起きない。
   * WebView では MainActivity が拾い、実際の遷移は起こらない。
   */
  function appCall(host) {
    try { window.location.href = "walldash://" + host; } catch (e) { /* 無視 */ }
  }

  function showToast(title, body, severe) {
    var el = $("toast");
    el.querySelector(".toast-title").textContent = title;
    el.querySelector(".toast-body").textContent = body;
    if (severe) el.classList.add("severe"); else el.classList.remove("severe");
    el.classList.add("show");
    // 減光中でも読めるように、出ている間だけ画面を明るくしてもらう
    appCall("wake");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.classList.remove("show");
      // 元が暗かったときだけ暗さに戻す（判断は Activity 側が持つ）
      appCall("restore");
    }, 5000);
  }

  function renderDisaster(d) {
    var card = $("cardDisaster");
    var body = $("disasterBody");
    if (!state.config.disaster.enabled) {
      card.classList.remove("active");
      keyed(body, "disabled", function (el) {
        html(el, '<div class="memo-empty">防災情報は未設定です。設定画面で地域を選んでください。</div>');
      });
      return;
    }
    if (!d || !d.available) {
      keyed(body, "unavailable", function (el) {
        html(el, '<div class="memo-empty">気象庁の情報を取得できていません。</div>');
      });
      return;
    }
    checkDisasterAlert(d);

    var active = d.activeAreas || [];
    var quake = (d.quakes || [])[0];
    var tsunami = d.tsunami || [];
    // 台風と噴火は設定で切れる。津波と警報・注意報は命に関わるので常に出す。
    var typhoons = disp().disasterShowTyphoon === false ? [] : (d.typhoons || []);
    var volcanoes = disp().disasterShowVolcano === false ? [] : (d.volcanoes || []);
    // 津波が出ている日はカードごと警戒色にする。警報・注意報と同じ扱い。
    if (active.length || tsunami.length) card.classList.add("active");
    else card.classList.remove("active");

    // 署名は「画面に出る内容」そのものから作る。取得時刻を混ぜると
    // 中身が同じまま描き直され、無駄な再描画になる。
    var sig = [d.headline || ""];
    for (var a = 0; a < active.length; a++) {
      sig.push(active[a].code + ":" + (active[a].kinds || []).join(","));
    }
    for (var ts = 0; ts < tsunami.length; ts++) {
      sig.push("ts:" + (tsunami[ts].title || "") + (tsunami[ts].reportedAt || ""));
    }
    for (var tp = 0; tp < typhoons.length; tp++) {
      sig.push("tp:" + (typhoons[tp].number || "") + (typhoons[tp].reportedAt || ""));
    }
    for (var vc = 0; vc < volcanoes.length; vc++) {
      sig.push("vc:" + volcanoes[vc].name + volcanoes[vc].level);
    }
    if (quake) sig.push(quake.occurredAt + "/" + quake.epicenter + "/" + quake.maxIntensity);

    keyed(body, sig.join("|"), function (el) {
      var i;
      var out = '<div class="dis-top">';

      // 津波は命に関わるので、気象警報より前、枠の最上段に出す。
      if (tsunami.length) {
        out += '<div class="dis-sec severe">津波</div>';
        for (i = 0; i < tsunami.length; i++) {
          out += '<div class="dis-row ts severe"><b></b><span class="num"></span></div>';
        }
      }

      out += active.length
        ? '<div class="dis-head"></div>'
        : '<div class="dis-head dim">発表中の警報・注意報はありません。</div>';
      // 地域ごとに発表中の種別を並べる。多いときはこの枠ごとスクロールして読む。
      for (i = 0; i < active.length; i++) {
        out += '<div class="dis-warn' + (active[i].severe ? " severe" : "") + '">' +
          '<b class="wname"></b><span class="wkinds"></span></div>';
      }

      // 台風は発生中のものだけ。中心気圧・進路・風速まで 3 行で出す。
      if (typhoons.length) {
        out += '<div class="dis-sec">台風</div>';
        for (i = 0; i < typhoons.length; i++) {
          out += '<div class="dis-tc"><div class="tc-head"><b></b><span></span></div>' +
            '<div class="tc-line"></div><div class="tc-line"></div></div>';
        }
      }

      // 噴火警報は全国ぶん。件数が多いので枠ごとスクロールして読む。
      if (volcanoes.length) {
        out += '<div class="dis-sec">噴火 <i>' + volcanoes.length + " 件</i></div>";
        for (i = 0; i < volcanoes.length; i++) {
          out += '<div class="dis-row vc' + (volcanoes[i].severe ? " severe" : "") +
            '"><b></b><span></span></div>';
        }
      }

      out += "</div>";
      // 発表中の下に空きがあるので、直近の地震 1 件をここに出す
      if (quake) {
        out += '<div class="dis-quake">' +
          '<span class="shindo ' + shindoClass(quake.maxIntensity) + '">震度 ' +
          (quake.maxIntensity || "—") + "</span>" +
          '<b class="qepi"></b>' +
          '<span class="dim num">M' + (quake.magnitude || "—") + " ・ " + hhmm(quake.occurredAt) + "</span>" +
          "</div>";
      }
      html(el, out);
      // 気象庁の本文・地域名・種別名は外部由来テキストなので textContent で入れる
      if (active.length) {
        var head = el.querySelector(".dis-head");
        if (head) head.textContent = d.headline || (active.length + " 地域で発表中");
      }
      var rows = el.querySelectorAll(".dis-warn");
      for (var r = 0; r < rows.length; r++) {
        rows[r].querySelector(".wname").textContent = active[r].name;
        rows[r].querySelector(".wkinds").textContent = (active[r].kinds || []).join("・");
      }
      // 気象庁由来の文字列はすべて textContent で入れる
      var tsRows = el.querySelectorAll(".dis-row.ts");
      for (var t2 = 0; t2 < tsRows.length; t2++) {
        tsRows[t2].querySelector("b").textContent = tsunami[t2].title || "津波情報";
        tsRows[t2].querySelector("span").textContent = hhmm(tsunami[t2].reportedAt);
      }
      var tcRows = el.querySelectorAll(".dis-tc");
      for (var t3 = 0; t3 < tcRows.length; t3++) {
        var tc = typhoonText(typhoons[t3]);
        tcRows[t3].querySelector(".tc-head b").textContent = tc.head;
        tcRows[t3].querySelector(".tc-head span").textContent = tc.kind;
        var lines = tcRows[t3].querySelectorAll(".tc-line");
        lines[0].textContent = tc.line1;
        lines[1].textContent = tc.line2;
      }
      var vcRows = el.querySelectorAll(".dis-row.vc");
      for (var v2 = 0; v2 < vcRows.length; v2++) {
        vcRows[v2].querySelector("b").textContent = volcanoes[v2].name;
        vcRows[v2].querySelector("span").textContent = volcanoes[v2].level;
      }
      if (quake) {
        var epi = el.querySelector(".qepi");
        if (epi) epi.textContent = quake.epicenter || "";
      }
      // 描き直したら必ず先頭から見せる。壁掛けなので、下のほうまで
      // スクロールされたまま放置されると津波・警報が見えなくなる。
      var top = el.querySelector(".dis-top");
      if (top) top.scrollTop = 0;
    });

    text($("disasterNote"), d.officeName || "");
  }

  // ---------------------------------------------------------------- 強震モニタ

  /*
   * 防災科研の強震モニタ。全国の観測点がいま揺れているかを色で示す画像で、
   * 「過去に何が起きたか」ではなく「いま揺れているか」が分かる。
   *
   * 画像は 1 秒ごとに公開されるが、壁掛けでそこまでの粒度は要らないので 3 秒ごとに取る。
   * 配信は平文 HTTP のみ（HTTPS は応答しない）。参考表示として扱う。
   */
  var KMONI_URL = "http://www.kmoni.bosai.go.jp/data/map_img/RealTimeImg/acmap_s/";
  /** 公開までのずれ。実測で 1 秒程度なので、取りこぼさないよう 2 秒下げる。 */
  var KMONI_DELAY_MS = 2000;
  var kmoniFailures = 0;

  function pad2(n) { return n < 10 ? "0" + n : String(n); }

  function kmoniTick() {
    // 出さない設定なら画像も取りに行かない。見えないものに 3 秒ごとの通信を使わない。
    if (disp().disasterShowKmoni === false) return;
    // 画像は JST のファイル名で並ぶ。端末のタイムゾーンに関係なく合わせるため、
    // UTC に 9 時間足して UTC 系の取得メソッドで読む。
    var d = new Date(Date.now() - KMONI_DELAY_MS + 9 * 3600 * 1000);
    var day = d.getUTCFullYear() + pad2(d.getUTCMonth() + 1) + pad2(d.getUTCDate());
    var stamp = day + pad2(d.getUTCHours()) + pad2(d.getUTCMinutes()) + pad2(d.getUTCSeconds());
    var url = KMONI_URL + day + "/" + stamp + ".acmap_s.gif";

    // 直接 src を差し替えると、失敗した瞬間に画像が消えて白い穴になる。
    // 先に読み込んでから差し替え、失敗時は前のコマを残す。
    var probe = new Image();
    probe.onload = function () {
      kmoniFailures = 0;
      $("kmoniLive").style.backgroundImage = 'url("' + url + '")';
      text($("kmoniTime"), pad2(d.getUTCHours()) + ":" + pad2(d.getUTCMinutes()) + ":" + pad2(d.getUTCSeconds()));
      $("kmoniTime").classList.remove("bad");
    };
    probe.onerror = function () {
      kmoniFailures++;
      // 一度の失敗は珍しくないので、続いたときだけ知らせる
      if (kmoniFailures >= 4) {
        text($("kmoniTime"), "取得不可");
        $("kmoniTime").classList.add("bad");
      }
    };
    probe.src = url;
  }

  // ---------------------------------------------------------------- Spotify

  /*
   * 再生中の曲。ジャケットと曲名だけを大きく出す。
   *
   * 画像は Spotify の CDN から直接読む。端末を経由させても絵は変わらないうえ、
   * 1.8GB の端末で画像をキャッシュに抱える理由がない。
   */
  function renderSpotify(sp) {
    var body = $("spotifyBody");
    var cfg = state.config.spotify || {};

    if (!cfg.enabled || !cfg.connected) {
      text($("spotifyNote"), "");
      spotifyLast = null;
      keyed(body, "off", function (el) {
        html(el, '<div class="sp-idle">Spotify は未連携です。<br>設定画面から連携してください。</div>');
      });
      return;
    }
    if (!sp || !sp.available) {
      text($("spotifyNote"), "");
      keyed(body, "unavailable|" + ((sp && sp.lastError) || ""), function (el) {
        html(el, '<div class="sp-idle"></div>');
        el.firstChild.textContent = (sp && sp.lastError)
          ? "取得できません: " + sp.lastError
          : "接続中…";
      });
      return;
    }
    // 一時停止中も曲は残っているので、止まっているとは扱わない。
    // ここで消すと、再開したくてもボタンが出ない。
    if (!sp.trackName) {
      text($("spotifyNote"), "停止中");
      spotifyLast = null;
      keyed(body, "idle", function (el) {
        html(el, '<div class="sp-idle">再生中の曲はありません。</div>');
      });
      return;
    }

    text($("spotifyNote"), sp.playing ? "再生中" : "一時停止中");
    spotifyLast = sp;

    /*
     * 下段（再生位置と操作ボタン）は設定で外せる。
     * 両方とも外すと下段そのものが無くなるので、空いた高さをジャケットと
     * 曲名に回して大きく出す（壁から離れても読めるようにするため）。
     */
    var showCtl = disp().spotifyShowControls !== false;
    var showTime = disp().spotifyShowProgress !== false;
    var big = !showCtl && !showTime;

    // 曲と再生状態が変わったときだけ描き直す。同じ絵を 5 秒ごとに読み直さないため。
    keyed(body,
      "t:" + sp.trackName + "|" + (sp.artistName || "") + "|" + (sp.albumImageUrl || "") +
        "|" + (sp.playing ? "1" : "0") + "|" + (showCtl ? "c" : "") + (showTime ? "p" : ""),
      function (el) {
        html(el,
          '<div class="sp-wrap' + (big ? " big" : "") + '">' +
          '<div class="sp-now">' +
          '<div class="sp-cover">' + (sp.albumImageUrl ? '<img alt="" src="">' : "") + "</div>" +
          '<div class="sp-meta"><div class="sp-track"></div><div class="sp-artist"></div></div>' +
          "</div>" +
          (big ? "" :
            '<div class="sp-foot">' +
            (showTime ? '<span class="sp-time num"></span>' : "<span></span>") +
            (showCtl
              ? '<div class="sp-controls">' +
                spButton("previous", "前の曲", "M7 6h2v12H7zm10 0v12l-8-6z") +
                (sp.playing
                  ? spButton("pause", "一時停止", "M7.5 6h3v12h-3zm6 0h3v12h-3z")
                  : spButton("play", "再生", "M8 5l11 7-11 7z")) +
                spButton("next", "次の曲", "M15 6h2v12h-2zM7 6l8 6-8 6z") +
                "</div>"
              : "") +
            "</div>") +
          "</div>");
        // 曲名・アーティスト名は外部由来なので textContent で入れる。
        // 画像の URL も同様に、属性へ直接組み立てず setAttribute で渡す。
        el.querySelector(".sp-track").textContent = sp.trackName;
        el.querySelector(".sp-artist").textContent = sp.artistName || "";
        var img = el.querySelector(".sp-cover img");
        if (img && sp.albumImageUrl) img.setAttribute("src", sp.albumImageUrl);
      });
    tickSpotifyTime();
  }

  function spButton(action, label, path) {
    return '<button type="button" data-sp="' + action + '" aria-label="' + label + '">' +
      '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">' +
      '<path fill="currentColor" d="' + path + '"/></svg></button>';
  }

  function mmss(ms) {
    var total = Math.max(0, Math.round(ms / 1000));
    var sec = total % 60;
    return Math.floor(total / 60) + ":" + (sec < 10 ? "0" : "") + sec;
  }

  /*
   * 再生位置は 5 秒おきにしか届かないので、そのまま出すと 5 秒ごとに数字が飛ぶ。
   * 受け取った時刻からの経過を足して、毎秒進んでいるように見せる。
   * サーバーは同じ端末なので、fetchedAt と Date.now() は同じ時計を指す。
   */
  function tickSpotifyTime() {
    var el = document.querySelector(".sp-time");
    if (!el) return;
    var sp = spotifyLast;
    if (!sp || sp.progressMs == null) { text(el, ""); return; }
    var pos = sp.progressMs;
    if (sp.playing && sp.fetchedAt) pos += Math.max(0, Date.now() - sp.fetchedAt);
    if (sp.durationMs != null) pos = Math.min(pos, sp.durationMs);
    text(el, mmss(pos) + (sp.durationMs != null ? " / " + mmss(sp.durationMs) : ""));
  }

  /*
   * 操作は Spotify 側で鳴っている端末に送る。このタブレットからは音は出ない。
   * 再生先が無いときや無料プランのときは Spotify が拒むので、理由を通知で出す。
   */
  function spotifyControl(action) {
    fetch("/api/spotify/control?action=" + encodeURIComponent(action), { method: "POST" })
      .then(function (r) {
        return r.json().then(function (data) {
          if (!r.ok) throw new Error((data && data.detail) || "HTTP " + r.status);
          return data;
        });
      })
      .then(function () { poll(); })
      .catch(function (e) { showToast("Spotify", e.message, false); });
  }

  // ---------------------------------------------------------------- ニュース

  function renderFeed(f) {
    var body = $("newsBody");
    if (!state.config.feed.enabled) {
      keyed(body, "disabled", function (el) {
        html(el, '<div class="memo-empty">フィードは未設定です。設定画面で RSS の URL を登録してください。</div>');
      });
      return;
    }
    var items = (f && f.items) || [];
    if (!items.length) {
      keyed(body, "empty|" + ((f && f.lastError) || ""), function (el) {
        html(el, '<div class="memo-empty">' + (f && f.lastError ? "取得できません" : "記事がありません") + "</div>");
      });
      return;
    }

    // 見出しが変わらない限り描き直さない。読んでいる途中で先頭へ戻さないため。
    var sig = [];
    for (var i = 0; i < items.length; i++) sig.push(items[i].title);

    keyed(body, sig.join("|"), function (el) {
      var out = "";
      for (var n = 0; n < items.length; n++) {
        out += '<div class="news-item"><div class="news-title"></div><div class="news-src"></div></div>';
      }
      html(el, out);
      var nodes = el.querySelectorAll(".news-item");
      for (var j = 0; j < nodes.length; j++) {
        // 外部フィードの文字列なので必ず textContent 経由で入れる
        nodes[j].querySelector(".news-title").textContent = items[j].title;
        nodes[j].querySelector(".news-src").textContent = items[j].source || "";
      }
    });

    text($("newsNote"), relative(f.fetchedAt));
  }

  // ---------------------------------------------------------------- 設定反映

  function applyConfig(cfg) {
    var d = cfg.display;
    document.documentElement.style.setProperty("--accent", d.accent);
    // CSS 側で rgba(var(--accent-rgb), 0.35) のように濃さを変えて使えるようにする
    accentRgb = hexToRgb(d.accent);
    document.documentElement.style.setProperty("--accent-rgb", accentRgb.join(", "));
    document.body.className = "layout-" + d.layout;

    var map = {
      cardClock: cfg.display.showClock,
      cardMemo: cfg.display.showMemo,
      cardWeather: cfg.display.showWeather,
      cardHourly: cfg.display.showHourly,
      cardDaily: cfg.display.showDaily,
      cardWifi: cfg.display.showWifi,
      cardStats: cfg.display.showDeviceStats,
      cardDisaster: cfg.display.showDisaster,
      cardNews: cfg.display.showFeed,
      cardTimer: cfg.display.showTimer,
      cardWord: cfg.display.showWord,
      cardSpotify: cfg.display.showSpotify
    };
    for (var id in map) {
      var el = $(id);
      if (!el) continue;
      // undefined は「未指定」であって「非表示」ではない。false のときだけ隠す。
      if (map[id] === false) el.classList.add("hidden"); else el.classList.remove("hidden");
    }

    // 時計の揃え
    var clock = $("cardClock");
    if (clock) {
      clock.classList.remove("al-left", "al-center", "al-right");
      clock.classList.add("al-" + (d.clockAlign || "left"));
    }
    // Wi-Fi の地球と、防災の強震モニタ。どちらも消すと隣の列が横いっぱいに広がる。
    toggleClass($("cardWifi"), "no-globe", d.wifiShowGlobe === false);
    toggleClass($("cardDisaster"), "no-kmoni", d.disasterShowKmoni === false);

    // ハムスターはカードではないので packCards() の対象外。
    // 消している間は歩く処理も止める（見えないものを 30fps で動かさない）。
    if (window.Hamster) window.Hamster.setVisible(d.showHamster !== false);

    packCards();
  }

  function toggleClass(el, name, on) {
    if (!el) return;
    if (on) el.classList.add(name); else el.classList.remove(name);
  }

  // ------------------------------------------------------------ 列の詰め直し

  /** main の grid-template-columns と合わせること。 */
  var COLUMNS = 24;

  /*
   * カードの素の幅（24 列中いくつ分か）。
   *
   * dashboard.css の .sN / .c-hourly / body.layout-* / @media(orientation:portrait) と
   * 同じ値をここに持つ。CSS から読み取る手も試したが、実機の WebView（Chrome 81 相当）は
   * getComputedStyle().gridColumnEnd に "span N" を返さないため当てにできない。
   * CSS 側の指定は JS が動かなかったときの見た目を保つために残してある。
   * 片方だけ変えると幅がずれるので、CSS を触ったらこの表も直すこと。
   */
  var SPAN_BASE = {
    cardClock: 8, cardWeather: 8, cardDisaster: 8, cardMemo: 10, cardHourly: 9,
    cardSpotify: 5, cardWifi: 6, cardStats: 10, cardNews: 8, cardDaily: 12,
    cardTimer: 6, cardWord: 6
  };

  /** レイアウト種別ごとの上書き（body.layout-clock / body.layout-weather）。 */
  var SPAN_LAYOUT = {
    clock: { cardClock: 12, cardWeather: 10, cardMemo: 14 },
    weather: { cardClock: 6, cardWeather: 10, cardHourly: 10 }
  };

  /** 縦向きでは横に 2 枚。時間別予報だけは目盛りが潰れるので全幅にする。 */
  var SPAN_PORTRAIT = { cardHourly: COLUMNS };
  var SPAN_PORTRAIT_DEFAULT = 12;

  function isPortrait() { return window.innerHeight > window.innerWidth; }

  function baseSpan(id, layout, portrait) {
    if (portrait) return SPAN_PORTRAIT[id] || SPAN_PORTRAIT_DEFAULT;
    var over = SPAN_LAYOUT[layout];
    if (over && over[id] != null) return over[id];
    return SPAN_BASE[id] || 8;
  }

  /*
   * 非表示のカードが空けた列を、同じ行に残ったカードへ配り直す。
   *
   * CSS の自動配置は span を縮めないので、1 枚隠すとその行の右端がそのまま空く
   * （時計 8 + 天気 8 + 防災 8 の行から天気を消すと、8 列ぶんの空白が残る）。
   * ここで行ごとの合計をちょうど COLUMNS 列にしてから grid-column を上書きする。
   */
  function packCards() {
    var list = document.querySelectorAll("main > .card");
    var layout = (state && state.config.display.layout) || "balanced";
    var portrait = isPortrait();
    var i;

    var visible = [];
    for (i = 0; i < list.length; i++) {
      if (list[i].classList.contains("hidden")) { list[i].style.gridColumn = ""; continue; }
      visible.push({ el: list[i], base: baseSpan(list[i].id, layout, portrait) });
    }
    if (!visible.length) return;

    // CSS の自動配置と同じ順で行に詰め、行の合計を COLUMNS ちょうどにして返す。
    var row = [], used = 0;
    for (i = 0; i < visible.length; i++) {
      if (used && used + visible[i].base > COLUMNS) {
        stretchRow(row, used);
        row = []; used = 0;
      }
      row.push(visible[i]);
      used += visible[i].base;
    }
    stretchRow(row, used);
  }

  /*
   * 余った列を、行内のカードへ元の幅に比例して配る。
   * 端数は最大剰余法（小数部の大きいカードから 1 列ずつ）で配り、
   * 合計が必ず COLUMNS になるようにする。切り捨てだけだと 1〜2 列の空白が残る。
   */
  function stretchRow(row, used) {
    var extra = COLUMNS - used, i;
    var span = [];
    for (i = 0; i < row.length; i++) span.push(row[i].base);

    if (extra > 0) {
      var rest = [], given = 0;
      for (i = 0; i < row.length; i++) {
        var exact = extra * row[i].base / used;
        var floor = Math.floor(exact);
        span[i] += floor;
        given += floor;
        rest.push({ i: i, frac: exact - floor });
      }
      rest.sort(function (a, b) { return b.frac - a.frac; });
      for (i = 0; i < extra - given; i++) span[rest[i].i] += 1;
    }

    for (i = 0; i < row.length; i++) row[i].el.style.gridColumn = "span " + span[i];
  }

  /*
   * 画面の向きが変わると素の幅が変わる。回転直後は古い寸法を読んでしまうので、
   * 少し置いてから詰め直す。
   */
  var packTimer = null;
  window.addEventListener("resize", function () {
    if (packTimer) clearTimeout(packTimer);
    packTimer = setTimeout(packCards, 200);
  });

  var shiftStep = 0;
  function applyBurnInShift() {
    if (!state || !state.config.display.burnInShiftEnabled) {
      $("shift").style.transform = "";
      return;
    }
    // 5 分ごとに ±2px 動かす。常時同じ画素を光らせ続けないための処置。
    var offsets = [[0, 0], [2, 1], [0, 2], [-2, 1], [-2, -1], [0, -2], [2, -1]];
    var o = offsets[shiftStep % offsets.length];
    shiftStep++;
    $("shift").style.transform = "translate(" + o[0] + "px," + o[1] + "px)";
  }

  // ---------------------------------------------------------------- 取得

  function render() {
    if (!state) return;
    if (state.config.configVersion !== configVersion) {
      configVersion = state.config.configVersion;
      applyConfig(state.config);
    }
    renderMemo(state.memo);
    renderWeather(state.weather);
    renderHourly(state.weather);
    renderDaily(state.weather);
    renderWifi(state.wifi, state.deviceStats);
    renderStats(state.deviceStats);
    renderDisaster(state.disaster);
    renderFeed(state.feed);
    renderWord(false);
    renderSpotify(state.spotify);
    refreshAges();
    text($("updated"), "更新 " + relative(Date.now()));
  }

  function poll() {
    fetch("/api/state", { cache: "no-store" })
      .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
      .then(function (data) {
        state = data;
        clockOffsetMs = 0; // サーバーは同一端末なのでズレ補正は不要
        failures = 0;
        $("offline").classList.remove("show");
        render();
      })
      .catch(function () {
        failures++;
        // 数回連続で落ちたときだけ出す。一瞬の失敗で壁に赤帯を出さない。
        if (failures >= 3) $("offline").classList.add("show");
      });
  }


  // ---------------------------------------------------------------- 今日の単語

  /*
   * 英検準1級レベルの語を 1 時間に 1 語。辞書はアプリに同梱している（words.js）。
   * 通信が切れても壁が空欄にならないことを優先した。
   */
  var wordHour = -1;

  function renderWord(force) {
    var hour = Math.floor(Date.now() / 3600000);
    if (!force && hour === wordHour) return;
    wordHour = hour;

    var w = Words.forHour(Date.now());
    html($("wordBody"),
      '<div class="word-main"></div>' +
      '<div class="word-ipa"></div>' +
      '<div class="word-meaning"><span class="word-pos"></span><b></b></div>');
    var body = $("wordBody");
    body.querySelector(".word-main").textContent = w.word;
    body.querySelector(".word-ipa").textContent = w.ipa;
    body.querySelector(".word-pos").textContent = w.pos;
    body.querySelector(".word-meaning b").textContent = w.meaning;
    text($("wordNote"), "毎時更新");
  }

  // ---------------------------------------------------------------- タイマー

  /*
   * 時・分をスクロールで選んで開始する簡易タイマー。
   *
   * 壁の前に立ったまま指だけで操作するので、数値入力ではなくドラムロール式にしている。
   * 残り時間は「終了時刻」で保持する。残り秒をカウントダウンすると、
   * 画面が暗転して描画が止まった間に時間がずれるため。
   * 鳴動音は Web Audio で作る（音声ファイルを同梱せずに済む）。
   */
  var TIMER_KEY = "walldash.timer";
  var TIMER_ROW = 26;          // ドラム 1 行の高さ(px)。CSS と合わせること。
  var timerMode = "idle";      // idle | running | ringing
  var timerEndAt = 0;
  var timerHours = 0;
  var timerMinutes = 5;
  var audioCtx = null;

  /*
   * 音量を当てるまでの待ち。
   *
   * 音量の変更は Activity 側（walldash://volume）で非同期に起きるので、
   * 先に鳴らすと変更前の音量で出てしまう。少しだけ置いてから鳴らす。
   */
  var VOLUME_SETTLE_MS = 180;
  var ringTimer = null;
  var ringLeft = 0;

  function pad2t(n) { return n < 10 ? "0" + n : String(n); }

  function saveTimer() {
    try {
      if (timerMode === "running") {
        localStorage.setItem(TIMER_KEY, String(timerEndAt));
      } else {
        localStorage.removeItem(TIMER_KEY);
      }
    } catch (e) { /* プライベートモード等では黙って諦める */ }
  }

  function restoreTimer() {
    try {
      var saved = Number(localStorage.getItem(TIMER_KEY));
      // 再読み込みを挟んでも動き続けるようにする。
      // 期限切れのものは拾わない（起動直後にいきなり鳴らさないため）。
      if (saved && saved > Date.now()) {
        timerEndAt = saved;
        timerMode = "running";
      }
    } catch (e) { /* 同上 */ }
  }

  /** 選択中の値を読む。中央に来た行が選択。 */
  function pickedValue(col) {
    return Math.round(col.scrollTop / TIMER_ROW);
  }

  function buildColumn(id, max) {
    var out = "";
    for (var i = 0; i <= max; i++) out += "<i>" + pad2t(i) + "</i>";
    html($(id), out);
  }

  function renderTimerIdle() {
    html($("timerBody"),
      '<div class="tm-set">' +
      '<div class="tm-picker">' +
      '<div class="tm-band"></div>' +
      '<div class="tm-col" id="tmH"></div>' +
      '<div class="tm-colon">:</div>' +
      '<div class="tm-col" id="tmM"></div>' +
      "</div>" +
      '<button type="button" id="tmStart">開始</button>' +
      "</div>");

    buildColumn("tmH", 23);
    buildColumn("tmM", 59);
    $("tmH").scrollTop = timerHours * TIMER_ROW;
    $("tmM").scrollTop = timerMinutes * TIMER_ROW;

    $("tmH").addEventListener("scroll", function () { timerHours = pickedValue(this); });
    $("tmM").addEventListener("scroll", function () { timerMinutes = pickedValue(this); });
    $("tmStart").addEventListener("click", startTimer);
    text($("timerNote"), "時 : 分");
  }

  function renderTimerRunning() {
    html($("timerBody"),
      '<div class="tm-run">' +
      '<div class="tm-left num" id="tmLeft">--:--</div>' +
      '<div class="tm-actions">' +
      '<button type="button" id="tmCancel" class="ghost">取消</button>' +
      "</div></div>");
    $("tmCancel").addEventListener("click", resetTimer);
    tickTimer();
  }

  function renderTimerRinging() {
    html($("timerBody"),
      '<div class="tm-ring">' +
      '<div class="tm-done">時間です</div>' +
      '<button type="button" id="tmStop">止める</button>' +
      "</div>");
    $("tmStop").addEventListener("click", resetTimer);
    text($("timerNote"), "");
  }

  function renderTimer() {
    if (timerMode === "running") renderTimerRunning();
    else if (timerMode === "ringing") renderTimerRinging();
    else renderTimerIdle();
  }

  function startTimer() {
    var total = timerHours * 3600 + timerMinutes * 60;
    if (total <= 0) return;
    // 開始のタップが唯一確実なユーザー操作なので、ここで音を鳴らす許可を取っておく
    ensureAudio();
    timerEndAt = Date.now() + total * 1000;
    timerMode = "running";
    saveTimer();
    renderTimer();
  }

  function resetTimer() {
    stopRing();
    timerMode = "idle";
    timerEndAt = 0;
    saveTimer();
    renderTimer();
  }

  /** 1 秒ごとに残りを書き換える。0 になったら鳴らす。 */
  function tickTimer() {
    if (timerMode !== "running") return;
    var left = Math.max(0, Math.round((timerEndAt - Date.now()) / 1000));
    var el = $("tmLeft");
    if (el) {
      var h = Math.floor(left / 3600);
      var m = Math.floor((left % 3600) / 60);
      var sec = left % 60;
      text(el, (h > 0 ? h + ":" + pad2t(m) : pad2t(m)) + ":" + pad2t(sec));
      if (left <= 10) el.classList.add("soon"); else el.classList.remove("soon");
    }
    if (left <= 0) {
      timerMode = "ringing";
      saveTimer();
      renderTimer();
      startRing();
    }
  }

  // ------------------------------------------------------------ 鳴動

  /** 通知設定。state が来る前でも既定で鳴らせるように既定値を返す。 */
  function notices() {
    return (state && state.config && state.config.notifications) || {};
  }

  /*
   * 通知音を鳴らす。
   *
   * 音量は WebAudio のゲインでは作らない。ゲインで絞ると端末の主音量に対する
   * 割合にしかならず、主音量が小さいときは通知も小さくなってしまう。
   * 壁掛けで欲しいのは逆で、「主音量が小さくても通知は設定した大きさで鳴る」こと。
   *
   * そこで Activity にメディア音量そのものを設定値まで動かしてもらい、
   * 当たるのを待ってから鳴らし、鳴り終わる頃に元の音量へ戻してもらう。
   * PC のブラウザで開発しているときは walldash:// が解決できないので、
   * 音量はブラウザ任せのまま音だけ鳴る。
   */
  function withNoticeVolume(soundMs, play) {
    ensureAudio();
    if (!audioCtx) return;
    // 戻すのは鳴り終わってから。余白を足しておかないと最後が切れる。
    appCall("volume?ms=" + Math.round(VOLUME_SETTLE_MS + soundMs + 400));
    setTimeout(play, VOLUME_SETTLE_MS);
  }

  function ensureAudio() {
    try {
      if (!audioCtx) {
        var Ctx = window.AudioContext || window.webkitAudioContext;
        if (Ctx) audioCtx = new Ctx();
      }
      // 端末によっては suspended で生成されるので、操作のあるうちに起こしておく
      if (audioCtx && audioCtx.state === "suspended") audioCtx.resume();
    } catch (e) { audioCtx = null; }
  }

  function beep(offsetSec) {
    if (!audioCtx) return;
    try {
      var osc = audioCtx.createOscillator();
      var gain = audioCtx.createGain();
      osc.type = "sine";
      osc.frequency.value = 880;
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      var t = audioCtx.currentTime + offsetSec;
      // 立ち上がり・立ち下がりを付けないと「プツッ」という雑音が乗る
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(0.4, t + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.32);
      osc.start(t);
      osc.stop(t + 0.36);
    } catch (e) { /* 音が出せなくても表示は続ける */ }
  }

  /**
   * 任意の音を 1 つ鳴らす。立ち上がり・立ち下がりを付けないと「プツッ」と雑音が乗る。
   */
  function tone(freq, offsetSec, durSec, type) {
    if (!audioCtx) return;
    try {
      var osc = audioCtx.createOscillator();
      var gain = audioCtx.createGain();
      osc.type = type;
      osc.frequency.value = freq;
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      var t = audioCtx.currentTime + offsetSec;
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(0.32, t + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + durSec);
      osc.start(t);
      osc.stop(t + durSec + 0.05);
    } catch (e) { /* 音が出せなくても表示は続ける */ }
  }

  /*
   * 防災情報の通知音。1 回だけ。
   * タイマーの鳴動（880Hz の正弦波を 3 連打、以後 2 秒ごとに繰り返し）と
   * 取り違えないよう、三角波で高い音から低い音へ 2 音だけ落とす。
   */
  /**
   * 短い合図を 1 回鳴らす。[周波数, 開始秒, 長さ秒] の並びを受け取る。
   * 鳴らし終わったら音声経路を閉じる（24 時間つけっぱなしの端末で起こしたままにしない）。
   * ただしタイマーが鳴っている最中は触らない。
   */
  function playCue(notes, type) {
    var i, endSec = 0;
    for (i = 0; i < notes.length; i++) {
      endSec = Math.max(endSec, notes[i][1] + notes[i][2]);
    }
    withNoticeVolume(endSec * 1000, function () {
      for (var j = 0; j < notes.length; j++) {
        tone(notes[j][0], notes[j][1], notes[j][2], type || "sine");
      }
      if (audioCloseTimer) clearTimeout(audioCloseTimer);
      audioCloseTimer = setTimeout(function () {
        if (timerMode === "ringing") return;
        try {
          if (audioCtx && audioCtx.state === "running") audioCtx.suspend();
        } catch (e) { /* 閉じられなくても動作には影響しない */ }
      }, 1600);
    });
  }

  function chime() {
    // 音を切っていても画面のバナーは出す。気づける手段を全部消さないため。
    if (notices().disasterSound === false) return;
    playCue([[1318.5, 0, 0.20], [987.8, 0.17, 0.36]], "triangle");
  }

  function startRing() {
    ensureAudio();
    ringLeft = 20;   // 2 秒間隔で約 40 秒。気づかないまま延々鳴らさない。
    ringPulse();
    ringTimer = setInterval(ringPulse, 2000);
  }

  /*
   * 3 連打を 2 秒ごとに繰り返す。1 回ごとに音量の保持を掛け直すので、
   * 鳴っているあいだは上げたままになり、止まれば最後の保持が切れて元へ戻る。
   */
  function ringPulse() {
    if (ringLeft-- <= 0) { stopRing(); return; }
    withNoticeVolume(1250, function () {
      beep(0);
      beep(0.45);
      beep(0.9);
    });
  }

  function stopRing() {
    if (ringTimer) { clearInterval(ringTimer); ringTimer = null; }
    ringLeft = 0;
    // 手で止めたときは、保持が切れるのを待たずにその場で音量を戻す
    appCall("volume?ms=0");
    // 鳴らし終わったら音声経路を閉じる。開けたままだと 24 時間つけっぱなしの端末で
    // オーディオが起きっぱなしになる。次に鳴らす前に ensureAudio() が起こし直す。
    try {
      if (audioCtx && audioCtx.state === "running") audioCtx.suspend();
    } catch (e) { /* 閉じられなくても動作には影響しない */ }
  }

  // ---------------------------------------------------------------- 設定パネル

  /*
   * 歯車で /settings を iframe に読み込み、タブレット単体で全設定を変えられるようにする。
   *
   * 明るさだけの簡易パネルを別に持っていたが、設定画面と項目が二重になり
   * 片方だけ古くなる。iframe なら設定の実体は 1 か所のままで、
   * 追加した項目も自動で歯車から触れる。
   * WebView からの要求は 127.0.0.1 なので loopback 扱いになり、認証は要らない。
   */
  function openPanel() {
    var frame = $("panelFrame");
    // 開くまで読み込まない。メモリ 1.8GB の端末で常時 2 画面分を抱えないため。
    if (frame.getAttribute("src") !== "/settings") frame.setAttribute("src", "/settings");
    $("panel").classList.remove("hidden");
  }

  function closePanel() {
    $("panel").classList.add("hidden");
    // 閉じたら解放する。次に開くときに読み直す方が、常駐させるより安い。
    $("panelFrame").removeAttribute("src");
    // 設定が変わっているかもしれないので、次の定期取得を待たずに反映する
    poll();
  }

  $("gearBtn").addEventListener("click", openPanel);
  /*
   * ブラウズは Activity 側の WebView を重ねて出す（MainActivity が walldash:// を拾う）。
   * iframe にしないのは、多くのサイトが X-Frame-Options で枠内表示を拒むため。
   * PC のブラウザで開発しているときは、このスキームが解決できず何も起きない。
   */
  $("browseBtn").addEventListener("click", function () {
    window.location.href = "walldash://browser";
  });

  /*
   * 全カードの取得をやり直す。各取得先は自前の間隔を持っているので、
   * 待たずに今の値にしたいときはサーバー側の /api/refresh に頼む。
   */
  $("refreshBtn").addEventListener("click", function () {
    var btn = this;
    btn.classList.add("busy");
    fetch("/api/refresh", { method: "POST" })
      .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
      .then(function (data) {
        state = data;
        failures = 0;
        $("offline").classList.remove("show");
        render();
      })
      .catch(function () { /* 失敗しても次の定期取得で追いつく */ })
      .then(function () { btn.classList.remove("busy"); });
  });

  // 操作ボタンは曲が変わるたびに作り直されるので、カード側で受ける
  $("spotifyBody").addEventListener("click", function (e) {
    var btn = e.target && e.target.closest ? e.target.closest("[data-sp]") : null;
    if (!btn) return;
    spotifyControl(btn.getAttribute("data-sp"));
  });
  $("panelClose").addEventListener("click", closePanel);
  // 背景をタップしたら閉じる（カード内のタップでは閉じない）
  $("panel").addEventListener("click", function (e) { if (e.target === $("panel")) closePanel(); });

  // ---------------------------------------------------------------- 起動

  renderClock();
  setInterval(function () { renderClock(); tickSpotifyTime(); }, 1000);
  poll();
  setInterval(poll, 2000);
  kmoniTick();
  setInterval(kmoniTick, 3000);
  renderWord(true);
  // 時刻をまたいだかは 1 分ごとに見れば十分
  setInterval(function () { renderWord(false); }, 60000);
  restoreTimer();
  renderTimer();
  setInterval(tickTimer, 1000);
  setInterval(applyBurnInShift, 300000);
})();
