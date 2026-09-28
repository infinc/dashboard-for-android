/*
 * PC・LAN の他端末から開く設定画面。USB(adb forward)経由の localhost からは認証なしで開ける。
 * LAN から開いた場合は先に PIN でログインしている必要がある。
 *
 * 各面の変更は画面上に溜めておき、左下の「全て保存」で 1 回にまとめて送る（POST /api/settings）。
 * 送る中身は collect() が画面上の全入力から組み立てる。読み込み直後の collect() と比べて、
 * 違えば「未保存の変更あり」とみなす。
 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var config = null;
  var device = null;
  var baseline = "";
  var pendingLocation = null;
  // 運行情報で選んでいる路線。一覧を読み込めていなくても、保存済みの選択を消さないよう入力とは別に持つ
  var trainSelected = [];
  var railwayChoices = [];
  var saving = false;
  // カードの配置。layoutSaved は保存する配置（空なら自動）、layoutRows は画面に描く横向きの並び（自動のときも幅つき）
  var layoutSaved = [];
  var layoutRows = [];
  var layoutAutoMessage = null;

  function api(path, options) {
    options = options || {};
    var init = { cache: "no-store", method: options.method || "GET" };
    if (options.body) {
      init.body = options.body;
      init.headers = { "Content-Type": "application/json" };
    }
    return fetch(path, init).then(function (res) {
      return res.text().then(function (t) {
        var data = t ? JSON.parse(t) : null;
        if (!res.ok) throw new Error((data && (data.detail || data.error)) || "HTTP " + res.status);
        return data;
      });
    });
  }

  function setStatus(id, message, kind) {
    var el = $(id);
    if (!el) return;
    el.textContent = message;
    el.className = "status" + (kind ? " " + kind : "");
  }

  function copy(o) {
    var out = {};
    for (var k in (o || {})) out[k] = o[k];
    return out;
  }

  function fillSelect(id, choices) {
    var el = $(id);
    el.innerHTML = "";
    for (var i = 0; i < choices.length; i++) {
      var opt = document.createElement("option");
      opt.value = choices[i].value;
      opt.textContent = choices[i].label;
      el.appendChild(opt);
    }
  }

  // ---------------------------------------------------------------- メニュー

  function showPane(name) {
    var items = document.querySelectorAll(".nav-item");
    var panes = document.querySelectorAll(".pane");
    var found = false;
    var i;
    for (i = 0; i < panes.length; i++) {
      var on = panes[i].getAttribute("data-pane") === name;
      if (on) found = true;
      panes[i].classList.toggle("active", on);
    }
    if (!found) return showPane("general");
    for (i = 0; i < items.length; i++) {
      items[i].classList.toggle("active", items[i].getAttribute("data-pane") === name);
    }
    $("panes").scrollTop = 0;
    if (location.hash !== "#" + name) location.hash = name;
  }

  (function bindNav() {
    var items = document.querySelectorAll(".nav-item");
    for (var i = 0; i < items.length; i++) {
      items[i].addEventListener("click", function () { showPane(this.getAttribute("data-pane")); });
    }
    window.addEventListener("hashchange", function () { showPane(location.hash.replace("#", "") || "general"); });
  })();

  // ---------------------------------------------------------------- 保存する中身

  /** 画面上のすべての入力から、保存する中身を組み立てる。 */
  function collect() {
    var display = copy(config.display);
    display.accent = $("accent").value;
    display.theme = $("theme").value;
    display.cardOpacity = Number($("cardOpacity").value) / 100;
    display.burnInShiftEnabled = $("burnIn").checked;
    display.normalBrightness = Number($("normalBrightness").value) / 100;
    display.idleDimEnabled = $("idleDimEnabled").checked;
    display.idleDimAfterSeconds = Number($("idleDimAfter").value);
    display.idleDimBrightness = Number($("idleDimBrightness").value) / 100;
    display.clockAlign = $("clockAlign").value;
    display.clockDateFormat = $("clockDateFormat").value;
    display.hourlyMode = $("hourlyMode").value;
    display.cardLayout = layoutSaved;
    // data-w の付いたチェックボックスはすべて display の真偽値
    var boxes = document.querySelectorAll("input[data-w]");
    for (var i = 0; i < boxes.length; i++) display[boxes[i].getAttribute("data-w")] = boxes[i].checked;
    // 天気の項目は並び順も意味を持つので、チェックボックスが置かれている順のまま配列にする
    var wx = document.querySelectorAll("input[data-wx]");
    display.weatherFields = [];
    for (var j = 0; j < wx.length; j++) if (wx[j].checked) display.weatherFields.push(wx[j].getAttribute("data-wx"));

    var units = copy(config.units);
    units.clock24h = $("clock24").value === "true";
    units.temperature = $("tempUnit").value;
    units.wind = $("windUnit").value;
    units.showSeconds = $("showSeconds").checked;

    var notifications = copy(config.notifications);
    notifications.disasterSound = $("disasterSound").checked;
    notifications.chargingSound = $("chargingSound").checked;
    notifications.disasterTone = $("disasterTone").value;
    notifications.chargingTone = $("chargingTone").value;
    notifications.timerTone = $("timerTone").value;
    notifications.volume = Number($("noticeVolume").value) / 100;

    var urls = [];
    var lines = $("feedUrls").value.split("\n");
    for (var k = 0; k < lines.length; k++) if (lines[k].trim()) urls.push(lines[k].trim());

    var stocks = [];
    var stockLines = $("stocksSymbols").value.split("\n");
    for (var s = 0; s < stockLines.length; s++) {
      var line = stockLines[s].trim();
      if (!line) continue;
      var symbol = line.split(/\s+/)[0];
      stocks.push({ symbol: symbol, label: line.slice(symbol.length).trim() || symbol });
    }

    var builtins = [];
    var cds = document.querySelectorAll("input[data-cd]");
    for (var c = 0; c < cds.length; c++) if (cds[c].checked) builtins.push(cds[c].getAttribute("data-cd"));
    var custom = [];
    var cdLines = $("countdownCustom").value.split("\n");
    for (var e = 0; e < cdLines.length; e++) {
      // アプリの Countdown.parseLines と同じ形:「名前 日付（時刻）」
      var m = /^(.+?)\s+(\d{1,4}[-/]\d{1,2}(?:[-/]\d{1,2})?(?:\s+\d{1,2}:\d{2})?)$/.exec(cdLines[e].trim());
      if (m) custom.push({ name: m[1].trim(), date: m[2].trim() });
    }

    var train = { enabled: $("trainEnabled").checked, railways: trainSelected.slice() };
    if ($("trainToken").value) train.token = $("trainToken").value;
    if ($("trainChallengeToken").value) train.challengeToken = $("trainChallengeToken").value;

    var calendar = {
      enabled: $("calendarEnabled").checked,
      mode: $("calendarMode").value,
      appleId: $("calendarAppleId").value.trim(),
      daysAhead: Number($("calendarDays").value) || 7
    };
    if ($("calendarPassword").value) calendar.password = $("calendarPassword").value;
    if ($("calendarIcsUrl").value) calendar.icsUrl = $("calendarIcsUrl").value;

    var photos = {
      enabled: $("photosEnabled").checked,
      intervalSec: Number($("photosInterval").value) || 60,
      shuffle: $("photosShuffle").checked
    };
    if ($("photosUrl").value) photos.albumUrl = $("photosUrl").value;

    var memo = {
      enabled: $("memoEnabled").checked,
      endpoint: $("memoEndpoint").value.trim(),
      pollIntervalMs: Number($("memoInterval").value) * 1000
    };
    // 空のままなら既存のトークンを変えない
    if ($("memoToken").value) memo.token = $("memoToken").value;

    return {
      settings: {
        location: pendingLocation || config.location,
        units: units,
        display: display,
        disaster: { enabled: $("disasterEnabled").checked, minIntensity: $("minIntensity").value },
        feed: { enabled: $("feedEnabled").checked, urls: urls, maxItems: Number($("feedMax").value) },
        notifications: notifications,
        stocks: { symbols: stocks, range: $("stocksRange").value },
        countdown: { builtins: builtins, custom: custom }
      },
      train: train,
      calendar: calendar,
      photos: photos,
      memo: memo,
      spotify: { enabled: $("spotifyEnabled").checked, clientId: $("spotifyClientId").value.trim() }
    };
  }

  function isDirty() {
    return config !== null && JSON.stringify(collect()) !== baseline;
  }

  function updateDirty() {
    var dirty = isDirty();
    $("saveAll").disabled = !dirty || saving;
    $("saveAll").textContent = dirty ? "全て保存" : "変更はありません";
    if (dirty) setStatus("saveStatus", "未保存の変更があります", "warn");
    else if ($("saveStatus").className.indexOf("warn") >= 0) setStatus("saveStatus", "");
  }

  document.addEventListener("input", updateDirty);
  document.addEventListener("change", updateDirty);

  // 未保存のままタブを閉じる・再読み込みする前に確かめる（文言はブラウザが決める）
  window.addEventListener("beforeunload", function (e) {
    if (!isDirty()) return;
    e.preventDefault();
    e.returnValue = "未保存の変更があります。";
  });

  // ---------------------------------------------------------------- 描画

  function renderDots() {
    var dots = document.querySelectorAll(".dot[data-w]");
    for (var i = 0; i < dots.length; i++) {
      dots[i].classList.toggle("off", config.display[dots[i].getAttribute("data-w")] === false);
    }
  }

  function render() {
    var d = config.display, u = config.units, n = config.notifications || {};
    fillSelect("accent", config.choices.accents);
    fillSelect("disasterTone", config.choices.tones);
    fillSelect("chargingTone", config.choices.tones);
    fillSelect("timerTone", config.choices.tones);

    $("accent").value = d.accent;
    $("theme").value = d.theme || "dark";
    $("cardOpacity").value = Math.round((d.cardOpacity == null ? 0.6 : d.cardOpacity) * 100);
    renderWallpaper();
    $("burnIn").checked = d.burnInShiftEnabled;
    $("normalBrightness").value = Math.round(d.normalBrightness * 100);
    $("idleDimEnabled").checked = d.idleDimEnabled !== false;
    $("idleDimAfter").value = d.idleDimAfterSeconds;
    $("idleDimBrightness").value = Math.round(d.idleDimBrightness * 100);

    pendingLocation = null;
    renderPlace();

    $("clock24").value = String(u.clock24h);
    $("showSeconds").checked = u.showSeconds;
    $("tempUnit").value = u.temperature;
    $("windUnit").value = u.wind;
    $("clockAlign").value = d.clockAlign || "left";
    $("clockDateFormat").value = d.clockDateFormat || "ja";
    $("hourlyMode").value = d.hourlyMode || "both";

    var st = config.stocks || { symbols: [], range: "1d" };
    $("stocksSymbols").value = st.symbols.map(function (x) { return x.symbol + " " + x.label; }).join("\n");
    $("stocksRange").value = st.range;
    var cd = config.countdown || { builtins: [], custom: [] };
    var cds = document.querySelectorAll("input[data-cd]");
    for (var c = 0; c < cds.length; c++) cds[c].checked = cd.builtins.indexOf(cds[c].getAttribute("data-cd")) >= 0;
    $("countdownCustom").value = cd.custom.map(function (x) { return x.name + " " + x.date; }).join("\n");

    var tr = config.train || {};
    $("trainEnabled").checked = !!tr.enabled;
    $("trainToken").value = "";
    $("trainToken").placeholder = tr.tokenSet ? "設定済み（変更する場合のみ入力）" : "未設定";
    $("trainChallengeToken").value = "";
    $("trainChallengeToken").placeholder = tr.challengeTokenSet ? "設定済み（変更する場合のみ入力）" : "未設定";
    trainSelected = (tr.railways || []).slice();
    renderRailways();

    var cal = config.calendar || {};
    $("calendarEnabled").checked = !!cal.enabled;
    $("calendarMode").value = cal.mode || "caldav";
    $("calendarAppleId").value = cal.appleId || "";
    $("calendarPassword").value = "";
    $("calendarPassword").placeholder = cal.passwordSet ? "設定済み（変更する場合のみ入力）" : "xxxx-xxxx-xxxx-xxxx";
    $("calendarIcsUrl").value = "";
    $("calendarIcsUrl").placeholder = cal.icsUrlSet ? "設定済み（変更する場合のみ入力）" : "webcal://p00-caldav.icloud.com/published/2/…";
    $("calendarDays").value = cal.daysAhead || 7;
    renderCalendarMode();

    var ph = config.photos || {};
    $("photosEnabled").checked = !!ph.enabled;
    $("photosUrl").value = "";
    $("photosUrl").placeholder = ph.albumUrlSet ? "設定済み（変更する場合のみ入力）" : "https://www.icloud.com/sharedalbum/#B0…";
    $("photosInterval").value = String(ph.intervalSec || 60);
    $("photosShuffle").checked = ph.shuffle !== false;

    var wx = document.querySelectorAll("input[data-wx]");
    for (var w = 0; w < wx.length; w++) wx[w].checked = d.weatherFields.indexOf(wx[w].getAttribute("data-wx")) >= 0;
    var boxes = document.querySelectorAll("input[data-w]");
    for (var i = 0; i < boxes.length; i++) boxes[i].checked = d[boxes[i].getAttribute("data-w")] !== false;
    renderDots();

    $("disasterEnabled").checked = config.disaster.enabled;
    $("minIntensity").value = config.disaster.minIntensity;

    $("feedEnabled").checked = config.feed.enabled;
    $("feedUrls").value = (config.feed.urls || []).join("\n");
    $("feedMax").value = config.feed.maxItems;

    $("memoEnabled").checked = config.memo.enabled;
    $("memoEndpoint").value = config.memo.endpoint;
    $("memoInterval").value = Math.round(config.memo.pollIntervalMs / 1000);
    $("memoToken").value = "";
    $("memoToken").placeholder = config.memo.tokenSet ? "設定済み（変更する場合のみ入力）" : "未設定 — Worker の DEVICE_TOKEN と同じ値";

    var sp = config.spotify || {};
    $("spotifyEnabled").checked = !!sp.enabled;
    $("spotifyClientId").value = sp.clientId || "";
    renderSpotify();

    $("disasterSound").checked = n.disasterSound !== false;
    $("chargingSound").checked = n.chargingSound !== false;
    $("disasterTone").value = n.disasterTone;
    $("chargingTone").value = n.chargingTone;
    $("timerTone").value = n.timerTone;
    $("noticeVolume").value = Math.round((n.volume == null ? 0.7 : n.volume) * 100);

    renderLan();
    updateRangeLabels();
    layoutSaved = copyRows(d.cardLayout || []);
    baseline = JSON.stringify(collect());
    updateDirty();
    checkLayout(collect().settings.display, collect().settings.display, true);
  }

  function renderPlace() {
    var loc = config.location;
    $("locNow").textContent = "現在の設定地点: " + loc.name + "（" + loc.timezone + "）";
    setStatus("placeStatus", pendingLocation ? "選択中: " + pendingLocation.name + " — 「全て保存」で反映されます" : "", pendingLocation ? "warn" : "");
    $("weatherPlace").textContent = loc.name;
  }

  /** 連携はタブレット本体か USB の PC から（Spotify の折り返し先が 127.0.0.1 固定のため）。 */
  function renderSpotify() {
    var sp = config.spotify || {};
    var local = location.hostname === "127.0.0.1" || location.hostname === "localhost";
    var saved = !!sp.clientId && $("spotifyClientId").value.trim() === sp.clientId;
    $("spotifyConnect").disabled = !saved || !local;
    $("spotifyDisconnect").disabled = !sp.connected;
    setStatus("spotifyLink", sp.connected
      ? "連携済み"
      : !local ? "連携はタブレット本体か、USB でつないだ PC のブラウザから行ってください"
        : saved ? "未連携 —「Spotify と連携」を押してください" : "Client ID を入力して「全て保存」すると連携できます");
  }

  function renderLan() {
    var on = config.lan.enabled;
    $("lanToggle").textContent = on ? "LAN 公開を無効にする" : "LAN 公開を有効にする";
    var url = device && device.lanUrl;
    setStatus("lanStatus", on
      ? (url ? "公開中 — 他の端末のブラウザで " + url + " を開き、PIN でログインしてください" : "公開中 — Wi-Fi の IP アドレスを取得できません")
      : (config.lan.pinSet ? "この端末と USB の PC からだけ開けます（PIN 設定済み）" : "この端末と USB の PC からだけ開けます（PIN 未設定）"),
      on ? "ok" : "");
  }

  function updateRangeLabels() {
    $("normalBrightnessValue").textContent = $("normalBrightness").value + "%";
    $("idleDimBrightnessValue").textContent = $("idleDimBrightness").value + "%";
    $("noticeVolumeValue").textContent = $("noticeVolume").value + "%";
    $("cardOpacityValue").textContent = $("cardOpacity").value + "%";
  }
  $("normalBrightness").addEventListener("input", updateRangeLabels);
  $("idleDimBrightness").addEventListener("input", updateRangeLabels);
  $("noticeVolume").addEventListener("input", updateRangeLabels);
  $("cardOpacity").addEventListener("input", updateRangeLabels);
  $("spotifyClientId").addEventListener("input", renderSpotify);

  function renderDevice() {
    var on = device.launcherHomeEnabled;
    $("launcherToggle").textContent = on ? "ホームアプリ登録を解除" : "ホームアプリとして登録";
    setStatus("launcherStatus", on
      ? "登録済み — 端末の既定ホームアプリに Walldash を選べます"
      : "未登録 — 再起動後は手動でアプリを開く必要があります");
    $("access").textContent = "待受 " + device.boundHost + ":" + device.port + " ／ 有効セッション " + device.activeSessions;
    renderLan();
  }

  function loadDevice() {
    return api("/api/device").then(function (d) { device = d; renderDevice(); });
  }

  /** 警報・注意報の地域は、天気の地点から端末が自動で決める。 */
  function loadDisasterArea() {
    return api("/api/state").then(function (s) {
      var d = (s && s.disaster) || {};
      var text;
      if (!config.disaster.enabled) text = "防災情報の取得が無効です";
      else if (!d.available) text = "まだ取得できていません";
      else if (!d.areaName) text = "天気の地点から市町村を決められません。「場所」で国内の地点を選んでください";
      else text = [d.officeName, d.areaName].filter(Boolean).join(" ");
      $("disasterArea").textContent = text;

      var cal = (s && s.calendar) || {};
      if (!config.calendar || !config.calendar.enabled) setStatus("calendarStatus", "取得は無効です");
      else if (cal.lastError) setStatus("calendarStatus", "エラー: " + cal.lastError, "err");
      else if (cal.fetchedAt > 0) setStatus("calendarStatus", "取得できています（" + (cal.events || []).length + " 件）", "ok");
      else setStatus("calendarStatus", "まだ取得していません —「全て保存」のあと少し待ってください");

      var ph = (s && s.photos) || {};
      if (!config.photos || !config.photos.enabled) setStatus("photosStatus", "取得は無効です");
      else if (ph.lastError) setStatus("photosStatus", "エラー: " + ph.lastError, "err");
      else if (ph.fetchedAt > 0) setStatus("photosStatus", "取得できています（" + (ph.albumName || "アルバム") + "、" + ph.count + " 枚）", "ok");
      else setStatus("photosStatus", "まだ取得していません —「全て保存」のあと少し待ってください（カードを表示しているときだけ取得します）");

      var tr = (s && s.train) || {};
      if (!config.train || !config.train.enabled) setStatus("trainStatus", "取得は無効です");
      else if (tr.fetchedAt > 0) setStatus("trainStatus", "取得できています" + (tr.lastError ? "（一部エラー: " + tr.lastError + "）" : ""), tr.lastError ? "warn" : "ok");
      else if (tr.lastError) setStatus("trainStatus", "エラー: " + tr.lastError, "err");
      else setStatus("trainStatus", "まだ取得していません —「全て保存」のあと少し待ってください");
    }).catch(function () { $("disasterArea").textContent = "取得できません"; });
  }

  // ---------------------------------------------------------------- 予定表・運行情報

  function renderCalendarMode() {
    var ics = $("calendarMode").value === "ics";
    $("calendarCaldav").style.display = ics ? "none" : "";
    $("calendarIcs").style.display = ics ? "" : "none";
  }
  $("calendarMode").addEventListener("change", renderCalendarMode);

  /** 路線の一覧（事業者ごと）。選んだ路線は trainSelected に持つ。 */
  function renderRailways() {
    var box = $("trainRailways");
    box.innerHTML = "";
    var group = "";
    for (var i = 0; i < railwayChoices.length; i++) {
      var r = railwayChoices[i];
      if (r.operator !== group) {
        group = r.operator;
        var head = document.createElement("div");
        head.className = "hint";
        head.style.width = "100%";
        head.textContent = group;
        box.appendChild(head);
      }
      var label = document.createElement("label");
      label.className = "toggle";
      var input = document.createElement("input");
      input.type = "checkbox";
      input.checked = trainSelected.indexOf(r.id) >= 0;
      input.setAttribute("data-rw", r.id);
      input.addEventListener("change", function () {
        var id = this.getAttribute("data-rw");
        if (this.checked) {
          if (trainSelected.length >= 12) { this.checked = false; setStatus("trainListStatus", "選べるのは 12 路線までです", "warn"); return; }
          if (trainSelected.indexOf(id) < 0) trainSelected.push(id);
        } else {
          trainSelected = trainSelected.filter(function (x) { return x !== id; });
        }
        updateDirty();
      });
      label.appendChild(input);
      label.appendChild(document.createTextNode(" " + r.title));
      box.appendChild(label);
    }
    if (!railwayChoices.length && trainSelected.length) {
      setStatus("trainListStatus", "選択中の路線: " + trainSelected.length + " 路線（一覧を読み込むと変更できます）");
    }
  }

  function loadRailways() {
    return api("/api/train/railways").then(function (list) { railwayChoices = list || []; renderRailways(); });
  }

  $("trainReload").addEventListener("click", function () {
    setStatus("trainListStatus", "読み込み中…");
    api("/api/train/railways/reload", { method: "POST", body: "{}" })
      .then(function (list) {
        railwayChoices = list || [];
        renderRailways();
        setStatus("trainListStatus", railwayChoices.length + " 路線を読み込みました", "ok");
      })
      .catch(function (e) { setStatus("trainListStatus", "エラー: " + e.message, "err"); });
  });

  $("trainClear").addEventListener("click", function () {
    trainSelected = [];
    renderRailways();
    updateDirty();
  });

  // ---------------------------------------------------------------- カードの数

  // ---------------------------------------------------------------- カードの配置

  function cardInfo(id) {
    var cards = config.choices.cards || [];
    for (var i = 0; i < cards.length; i++) if (cards[i].id === id) return cards[i];
    return { id: id, label: id, span: 6, min: 1 };
  }

  function copyRows(rows) {
    return rows.map(function (row) {
      return row.map(function (x) { return { card: x.card, span: x.span, height: x.height || 1 }; });
    });
  }

  /**
   * タブレットに配置を合わせ直してもらう（判定と並べ方はアプリの CardLayout.adjust と同じ）。
   * 収まるならその配置を下書きにし、収まらないなら false を返す。[keep] のときは保存する配置を変えず、描く並びだけ受け取る。
   */
  function checkLayout(before, after, keep) {
    return api("/api/layout/check", { method: "POST", body: JSON.stringify({ before: before, after: after }) })
      .then(function (r) {
        if (!r.ok) return r;
        if (!keep) layoutSaved = copyRows(r.layout || []);
        layoutRows = copyRows(r.rows || []);
        layoutAutoMessage = r.autoMessage || null;
        renderLayout();
        updateDirty();
        return r;
      });
  }

  var COLUMNS = 24;
  function layoutLimit() { return (config.choices && config.choices.layoutRows) || 4; }

  /**
   * 各カードの位置（アプリの CardLayout.positions と同じ）。行の中では左から詰め、上の行から縦に伸びてきたカードの列は飛ばす。
   * 右端を越えるカードか、[limit] 行より下へ伸びるカードがあれば null。
   */
  function positions(rows, limit) {
    var blocked = {};
    var out = [];
    for (var r = 0; r < rows.length; r++) {
      var x = 0;
      for (var i = 0; i < rows[r].length; i++) {
        var t = rows[r][i];
        var h = t.height || 1;
        if (r + h > limit) return null;
        for (;;) {
          var hit = -1;
          for (var q = r; q < r + h; q++) {
            (blocked[q] || []).forEach(function (b) { if (b[0] < x + t.span && x < b[1]) hit = Math.max(hit, b[1]); });
          }
          if (hit < 0) break;
          x = hit;
        }
        if (x + t.span > COLUMNS) return null;
        out.push({ card: t.card, row: r, index: i, col: x, span: t.span, height: h });
        for (var below = r + 1; below < r + h; below++) (blocked[below] = blocked[below] || []).push([x, x + t.span]);
        x += t.span;
      }
    }
    return out;
  }
  function fits(rows) { return positions(rows, layoutLimit()) !== null; }
  function flat(rows) { return copyRows(rows).map(function (row) { return row.map(function (x) { x.height = 1; return x; }); }); }
  function placedOf(rows) { return positions(rows, Infinity) || positions(flat(rows), Infinity); }

  /** [r] 行目に並べられる列の数（上の行から縦に伸びてきたカードの分を除く）。 */
  function capacity(rows, r) {
    var used = 0;
    for (var q = 0; q < Math.min(r, rows.length); q++) {
      rows[q].forEach(function (x) { if (q + (x.height || 1) > r) used += x.span; });
    }
    return COLUMNS - used;
  }

  /**
   * 行の [index] 番目のカードの右の壁を [delta] 列動かす（アプリの CardLayout.resize と同じ）。
   * 右へ: 右隣を最小の幅まで縮め、足りなければ行の右端の空きを使う。左へ: 自分を最小の幅まで縮め、その分を右隣へ渡す。
   */
  function resizeRow(row, index, delta) {
    var out = copyRows([row])[0];
    var next = index + 1;
    var total = function () { return out.reduce(function (a, x) { return a + x.span; }, 0); };
    if (delta > 0) {
      var grow = delta;
      if (next < out.length) {
        var take = Math.max(0, Math.min(grow, out[next].span - cardInfo(out[next].card).min));
        out[next].span -= take;
        out[index].span += take;
        grow -= take;
      }
      out[index].span += Math.max(0, Math.min(grow, COLUMNS - total()));
    } else if (delta < 0) {
      var shrink = Math.max(0, Math.min(-delta, out[index].span - cardInfo(out[index].card).min));
      out[index].span -= shrink;
      if (next < out.length) out[next].span += shrink;
    }
    return out;
  }

  /** 幅を変える。縦に伸ばしたカードとぶつかるなら、ぶつからない所までにとどめる。 */
  function resizeIn(rows, r, index, delta) {
    for (var step = delta; step !== 0; step -= Math.sign(step)) {
      var next = copyRows(rows);
      next[r] = resizeRow(rows[r], index, step);
      if (fits(next)) return next;
    }
    return rows;
  }

  /** 高さを [height] 行にする（アプリの CardLayout.setHeight と同じ）。下の行に空きが足りなければ、入る所までにとどめる。 */
  function setHeight(rows, r, index, height) {
    var now = rows[r][index].height || 1;
    var h = Math.max(1, Math.min(height, layoutLimit() - r));
    while (h !== now) {
      var next = copyRows(rows);
      next[r][index].height = h;
      if (fits(next)) return next;
      h += h > now ? -1 : 1;
    }
    return rows;
  }

  /** 末尾の空の行を落とす（途中の空の行は、4 行のどこに置いたかを保つため残す）。 */
  function trimRows(rows) {
    var out = rows.slice();
    while (out.length && !out[out.length - 1].length) out.pop();
    return out;
  }

  /**
   * 行の [at] 番目に [card] を入れる（アプリの CardLayout.squeeze と同じ）。空きが最小の幅に足りなければ、
   * ほかのカードを最小の幅を超えている分の多い順に 1 列ずつ縮め、そのあと [want] 列になるまで少しずつ分けてもらう。
   */
  function squeezeRow(row, card, want, at, height, cap) {
    var out = copyRows([row])[0];
    var min = cardInfo(card).min;
    var excess = function (x) { return x.span - cardInfo(x.card).min; };
    var widest = function () { return out.slice().sort(function (p, q) { return excess(q) - excess(p); })[0]; };
    var total = function () { return out.reduce(function (a, x) { return a + x.span; }, 0); };
    while (cap - total() < min) widest().span--;
    var span = min;
    while (span < want) {
      if (cap - total() - span > 0) { span++; continue; }
      var w = widest();
      if (!w || excess(w) <= span - min + 1) break;
      w.span--;
      span++;
    }
    out.splice(Math.max(0, Math.min(at, out.length)), 0, { card: card, span: span, height: height });
    return out;
  }

  /** 行 [toRow] の [toIndex] 番目に [tile] を入れる（アプリの CardLayout.putAt と同じ）。入らなければ null。 */
  function putAt(rows, tile, toRow, toIndex) {
    var grid = copyRows(rows);
    while (grid.length <= toRow) grid.push([]);
    var target = grid[toRow];
    var at = Math.max(0, Math.min(toIndex, target.length));
    var cap = capacity(grid, toRow);
    var used = target.reduce(function (a, x) { return a + x.span; }, 0);
    var mins = target.reduce(function (a, x) { return a + cardInfo(x.card).min; }, 0);
    function attempt(t) {
      var row;
      if (cap - used >= t.span) { row = copyRows([target])[0]; row.splice(at, 0, t); }
      else if (cap - mins >= cardInfo(t.card).min) row = squeezeRow(target, t.card, t.span, at, t.height, cap);
      else return null;
      var next = copyRows(grid);
      next[toRow] = row;
      next = trimRows(next);
      return fits(next) ? next : null;
    }
    var h = Math.max(1, Math.min(tile.height || 1, layoutLimit() - toRow));
    return attempt({ card: tile.card, span: tile.span, height: h }) ||
      (h > 1 ? attempt({ card: tile.card, span: tile.span, height: 1 }) : null);
  }

  function removeCard(rows, r, index) {
    var grid = copyRows(rows);
    grid[r].splice(index, 1);
    return trimRows(grid);
  }

  /**
   * カードを [fromRow] 行の [fromIndex] 番目から [toRow] 行の [toIndex] 番目（抜いたあとの位置）へ動かす（アプリの CardLayout.move と同じ）。
   * 行き先の行のカードを最小の幅まで縮めても入らなければ null。
   */
  function moveCard(rows, fromRow, fromIndex, toRow, toIndex) {
    var tile = copyRows(rows)[fromRow][fromIndex];
    var grid = copyRows(rows);
    grid[fromRow].splice(fromIndex, 1);
    if (toRow === fromRow) {
      grid[toRow].splice(Math.max(0, Math.min(toIndex, grid[toRow].length)), 0, tile);
      grid = trimRows(grid);
      if (fits(grid)) return grid;
      return putAt(removeCard(rows, fromRow, fromIndex), { card: tile.card, span: tile.span, height: 1 }, toRow, toIndex);
    }
    return putAt(grid, tile, toRow, toIndex);
  }

  /** 使っていないカードを足す（元の幅で。足りなければほかを縮める）。 */
  function insertCard(rows, card, toRow, toIndex) {
    return putAt(rows, { card: card, span: cardInfo(card).span, height: 1 }, toRow, toIndex);
  }

  /** カードの表示のチェックボックス（「使っていないカード」はここが外れているもの）。 */
  function cardBox(card) {
    var flag = cardInfo(card).flag;
    return flag ? document.querySelector('input[data-w="' + flag + '"]') : null;
  }
  function unusedCards() {
    return (config.choices.cards || []).filter(function (c) { var box = cardBox(c.id); return box && !box.checked; });
  }

  // 幅・高さを変えている最中（drag）と、カードを動かしている最中（move）
  var drag = null;
  var move = null;
  var ROW_H = 74, ROW_GAP = 8, INSET = 6;

  function cardEl(info, span, height, removing) {
    var card = document.createElement("div");
    card.className = "lay-card";
    var name = document.createElement("div");
    name.className = "lay-name";
    name.textContent = info.label;
    var width = document.createElement("div");
    var min = span <= info.min;
    width.className = "lay-span" + (removing ? " bad" : min ? " min" : "");
    width.textContent = removing ? "離すと外します" : span + " 列" + (height > 1 ? " × " + height + " 行" : "") + (min ? "（最小）" : "");
    card.appendChild(name);
    card.appendChild(width);
    return card;
  }

  function renderLayout() {
    var root = $("layoutEditor");
    var count = Math.max(layoutLimit(), layoutRows.length);
    root.innerHTML = "";
    root.style.height = (count * ROW_H + (count - 1) * ROW_GAP) + "px";
    // 動かしている最中は、元の場所からそのカードを抜いて描く（差し込む位置の線と揃えるため）
    var shown = move && move.started && !move.tray ? removeCard(layoutRows, move.row, move.index) : layoutRows;
    var placed = placedOf(shown);
    for (var r = 0; r < count; r++) {
      var el = document.createElement("div");
      var target = move && move.drop && !move.drop.remove && move.drop.row === r ? move.drop : null;
      el.className = "lay-row" + (target ? " target" : "");
      el.style.top = (r * (ROW_H + ROW_GAP)) + "px";
      el.setAttribute("data-row", r);
      if (target) {
        var mark = document.createElement("div");
        mark.className = "lay-drop" + (target.fits ? "" : " bad");
        mark.style.left = (target.col / COLUMNS * 100) + "%";
        el.appendChild(mark);
      } else {
        // いちばん広い空き（上から伸びてきたカードの下も使っている扱い）に、空いている列の数を出す
        var used = [];
        placed.forEach(function (p) { if (r >= p.row && r < p.row + p.height) for (var c = p.col; c < p.col + p.span; c++) used[c] = true; });
        var best = null, start = -1;
        for (var c = 0; c <= COLUMNS; c++) {
          var free = c < COLUMNS && !used[c];
          if (free && start < 0) start = c;
          if (!free && start >= 0) { if (!best || c - start > best[1]) best = [start, c - start]; start = -1; }
        }
        if (best && best[1] >= 2) {
          var label = document.createElement("div");
          label.className = "lay-free";
          label.style.left = (best[0] / COLUMNS * 100) + "%";
          label.textContent = best[1] === COLUMNS ? "空いている行" : "余白 " + best[1] + " 列";
          el.appendChild(label);
        }
      }
      root.appendChild(el);
    }
    placed.forEach(function (p) {
      var info = cardInfo(p.card);
      var active = drag && drag.row === p.row && drag.index === p.index;
      var card = cardEl(info, p.span, p.height, false);
      if (active) card.className += " active";
      card.style.left = "calc(" + (p.col / COLUMNS * 100) + "% + 3px)";
      card.style.width = "calc(" + (p.span / COLUMNS * 100) + "% - 6px)";
      card.style.top = (p.row * (ROW_H + ROW_GAP) + INSET) + "px";
      card.style.height = (p.height * ROW_H + (p.height - 1) * ROW_GAP - INSET * 2) + "px";
      card.setAttribute("data-row", p.row);
      card.setAttribute("data-index", p.index);
      root.appendChild(card);
      if (move) return;
      // 右の壁のつまみ（幅）と、下の壁のつまみ（高さ）
      var grip = document.createElement("div");
      grip.className = "lay-grip" + (active && drag.axis === "x" ? " active" : "");
      grip.style.left = ((p.col + p.span) / COLUMNS * 100) + "%";
      grip.style.top = card.style.top;
      grip.style.height = card.style.height;
      grip.setAttribute("data-row", p.row);
      grip.setAttribute("data-index", p.index);
      grip.title = "ドラッグして幅を変える";
      root.appendChild(grip);
      var vgrip = document.createElement("div");
      vgrip.className = "lay-vgrip" + (active && drag.axis === "y" ? " active" : "");
      vgrip.style.left = "calc(" + ((p.col + p.span / 2) / COLUMNS * 100) + "% - 20px)";
      vgrip.style.top = (p.row * (ROW_H + ROW_GAP) + INSET + p.height * ROW_H + (p.height - 1) * ROW_GAP - INSET * 2 - 10) + "px";
      vgrip.setAttribute("data-row", p.row);
      vgrip.setAttribute("data-index", p.index);
      vgrip.title = "ドラッグして高さを変える";
      root.appendChild(vgrip);
    });

    // 使っていないカード
    var tray = $("layoutTray");
    tray.innerHTML = "";
    var unused = unusedCards();
    $("layoutTrayLabel").textContent = unused.length
      ? "使っていないカード（掴んで上の枠へ動かすと足せます）"
      : "使っていないカードはありません";
    $("layoutTrayLabel").className = "lay-tray-label" + (move && move.drop && move.drop.remove ? " bad" : "");
    unused.forEach(function (c) {
      if (move && move.tray && move.card === c.id && move.started) return;
      var chip = document.createElement("div");
      chip.className = "lay-chip";
      chip.setAttribute("data-card", c.id);
      chip.innerHTML = '<div class="lay-name"></div><div class="lay-span"></div>';
      chip.firstChild.textContent = c.label;
      chip.lastChild.textContent = c.span + " 列";
      tray.appendChild(chip);
    });

    var custom = layoutSaved.length > 0;
    $("layoutAuto").disabled = !custom;
    setStatus("layoutMode", custom ? "自分で決めた配置です" : "いまは自動で並べています（幅や場所を動かすと、自分で決めた配置になります）");
  }

  function commitRows(rows) {
    layoutRows = rows;
    layoutSaved = trimRows(copyRows(rows));
    renderLayout();
    updateDirty();
  }

  /** 指やマウスの位置から、離したときにどうなるか（入る行と位置、または枠の外で外す）を決める。 */
  function dropAt(x, y) {
    var rect = $("layoutEditor").getBoundingClientRect();
    if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) {
      return { row: -1, index: 0, col: 0, fits: !move.tray, remove: !move.tray };
    }
    var count = Math.max(layoutLimit(), layoutRows.length);
    var r = Math.max(0, Math.min(count - 1, Math.floor((y - rect.top) / (ROW_H + ROW_GAP))));
    var col = rect.width / COLUMNS;
    var base = move.tray ? layoutRows : removeCard(layoutRows, move.row, move.index);
    var index = 0, mark = 0;
    placedOf(base).forEach(function (p) {
      if (p.row !== r) return;
      if (rect.left + (p.col + p.span / 2) * col < x) { index++; mark = p.col + p.span; }
    });
    var next = move.tray ? insertCard(layoutRows, move.card, r, index) : moveCard(layoutRows, move.row, move.index, r, index);
    return { row: r, index: index, col: mark, fits: next !== null, remove: false };
  }

  // つまみを掴んだら幅・高さを変え、カードを掴んで動かしたら置き場所を変える（描き直してもドラッグが切れないよう、動きは document で受ける）
  function startMove(e, el, source) {
    e.preventDefault();
    var rect = el.getBoundingClientRect();
    move = source;
    move.startX = e.clientX; move.startY = e.clientY;
    move.dx = e.clientX - rect.left; move.dy = e.clientY - rect.top;
    move.width = rect.width; move.height = rect.height;
    move.started = false; move.drop = null; move.ghost = null;
  }
  $("layoutEditor").addEventListener("pointerdown", function (e) {
    var grip = e.target.closest ? e.target.closest(".lay-grip, .lay-vgrip") : null;
    if (grip) {
      e.preventDefault();
      var r = Number(grip.getAttribute("data-row"));
      drag = {
        axis: grip.classList.contains("lay-vgrip") ? "y" : "x",
        row: r,
        index: Number(grip.getAttribute("data-index")),
        x: e.clientX,
        y: e.clientY,
        column: $("layoutEditor").getBoundingClientRect().width / COLUMNS,
        start: copyRows(layoutRows)
      };
      renderLayout();
      return;
    }
    var card = e.target.closest ? e.target.closest(".lay-card") : null;
    if (!card) return;
    var row = Number(card.getAttribute("data-row")), index = Number(card.getAttribute("data-index"));
    startMove(e, card, { tray: false, row: row, index: index, card: layoutRows[row][index].card });
  });
  $("layoutTray").addEventListener("pointerdown", function (e) {
    var chip = e.target.closest ? e.target.closest(".lay-chip") : null;
    if (!chip) return;
    var id = chip.getAttribute("data-card");
    startMove(e, chip, { tray: true, row: -1, index: -1, card: id });
    // 置き場の札は小さいので、持ち上げたら枠の中の元の幅の大きさにする
    move.width = $("layoutEditor").getBoundingClientRect().width / COLUMNS * cardInfo(id).span - 6;
    move.height = ROW_H - INSET * 2;
    move.dx = Math.min(move.dx, move.width / 2);
  });
  document.addEventListener("pointermove", function (e) {
    if (drag) {
      var next = drag.axis === "x"
        ? resizeIn(drag.start, drag.row, drag.index, Math.round((e.clientX - drag.x) / drag.column))
        : setHeight(drag.start, drag.row, drag.index, (drag.start[drag.row][drag.index].height || 1) + Math.round((e.clientY - drag.y) / (ROW_H + ROW_GAP)));
      if (JSON.stringify(next) === JSON.stringify(layoutRows)) return;
      commitRows(next);
      return;
    }
    if (!move) return;
    // 少し動かしてから持ち上げる（クリックだけで並びが崩れないように）
    if (!move.started) {
      if (Math.abs(e.clientX - move.startX) + Math.abs(e.clientY - move.startY) < 6) return;
      move.started = true;
      var ghost = document.createElement("div");
      ghost.className = "lay-card lay-ghost";
      ghost.style.width = move.width + "px";
      ghost.style.height = move.height + "px";
      document.body.appendChild(ghost);
      move.ghost = ghost;
    }
    var drop = dropAt(e.clientX, e.clientY);
    var info = cardInfo(move.card);
    var span = move.tray ? info.span : layoutRows[move.row][move.index].span;
    var height = move.tray || drop.remove ? 1 : layoutRows[move.row][move.index].height || 1;
    var content = cardEl(info, span, height, drop.remove);
    move.ghost.innerHTML = content.innerHTML;
    move.ghost.className = "lay-card lay-ghost" + (drop.remove ? " removing" : "");
    move.ghost.style.height = (drop.remove || move.tray ? ROW_H - INSET * 2 : move.height) + "px";
    move.ghost.style.left = (e.clientX - move.dx) + "px";
    move.ghost.style.top = (e.clientY - Math.min(move.dy, parseFloat(move.ghost.style.height) - 4)) + "px";
    if (!move.drop || JSON.stringify(drop) !== JSON.stringify(move.drop)) {
      move.drop = drop;
      renderLayout();
    }
  });
  function endDrag() {
    if (drag) {
      drag = null;
      renderLayout();
      return;
    }
    if (!move) return;
    var m = move;
    move = null;
    if (m.ghost) m.ghost.parentNode.removeChild(m.ghost);
    var d = m.drop;
    var info = cardInfo(m.card);
    if (!m.started || !d || d.row < 0 && !d.remove || (!m.tray && d.row === m.row && d.index === m.index)) { renderLayout(); return; }
    if (d.remove) {
      // 枠の外で離したら外す（非表示にする）
      cardBox(m.card).checked = false;
      commitRows(removeCard(layoutRows, m.row, m.index));
      return;
    }
    var next = m.tray ? insertCard(layoutRows, m.card, d.row, d.index) : moveCard(layoutRows, m.row, m.index, d.row, d.index);
    if (!next) {
      renderLayout();
      alert("ここには入りません\n\n「" + info.label + "」は、行き先の行のカードをいちばん狭い幅まで縮めても入りません（最小の幅 " + info.min +
        " 列）。ほかの行を選ぶか、先に行き先の行のカードを動かしてください。");
      return;
    }
    if (m.tray) cardBox(m.card).checked = true;
    commitRows(next);
  }
  document.addEventListener("pointerup", endDrag);
  document.addEventListener("pointercancel", endDrag);

  $("layoutAuto").addEventListener("click", function () {
    if (layoutAutoMessage) {
      alert("自動の並べ方に戻せません\n\n" + layoutAutoMessage);
      return;
    }
    var before = collect().settings.display;
    layoutSaved = [];
    checkLayout(before, collect().settings.display).catch(function () {});
  });

  /*
   * カードの表示を切り替えたら、タブレットに配置を合わせ直してもらう（判定はアプリと同じ計算）。
   * 空きが足りなければほかのカードを最小の幅まで縮めて入れる。それでも入らないなら理由を出してチェックを戻す。
   * 確かめられないとき（通信の失敗）は止めず、保存時の検査に任せる。
   */
  (function bindCardChecks() {
    var boxes = document.querySelectorAll("input[data-card]");
    for (var i = 0; i < boxes.length; i++) {
      boxes[i].addEventListener("change", function () {
        var box = this;
        var on = box.checked;
        var after = collect().settings.display;
        box.checked = !on;
        var before = collect().settings.display;
        box.checked = on;
        checkLayout(before, after)
          .then(function (r) {
            if (r.ok) return;
            box.checked = !on;
            updateDirty();
            alert("カードを増やせません\n\n" + r.message);
          })
          .catch(function () {});
      });
    }
  })();

  // ---------------------------------------------------------------- 全て保存

  $("saveAll").addEventListener("click", function () {
    if (!isDirty()) return;
    saving = true;
    updateDirty();
    setStatus("saveStatus", "保存中…");
    api("/api/settings", { method: "POST", body: JSON.stringify(collect()) })
      .then(function (updated) {
        config = updated;
        render();
        setStatus("saveStatus", "保存しました", "ok");
        // 地点を変えたときは、端末が市町村を決め直すまで少し待ってから表示し直す
        setTimeout(loadDisasterArea, 3000);
      })
      .catch(function (e) { setStatus("saveStatus", "エラー: " + e.message, "err"); })
      .then(function () { saving = false; updateDirty(); });
  });

  // ---------------------------------------------------------------- 試聴（タブレットから鳴る）

  function bindPreview(buttonId, selectId) {
    $(buttonId).addEventListener("click", function () {
      var volume = Number($("noticeVolume").value) / 100;
      api("/api/sound/preview?tone=" + encodeURIComponent($(selectId).value) + "&volume=" + volume, { method: "POST" })
        .catch(function (e) { setStatus("saveStatus", "エラー: " + e.message, "err"); });
    });
  }
  bindPreview("previewDisaster", "disasterTone");
  bindPreview("previewCharging", "chargingTone");
  bindPreview("previewTimer", "timerTone");

  // ---------------------------------------------------------------- 場所

  $("search").addEventListener("click", function () {
    var q = $("q").value.trim();
    if (!q) return;
    $("results").textContent = "検索中…";
    api("/api/geocode?q=" + encodeURIComponent(q)).then(function (list) {
      $("results").innerHTML = "";
      if (!list.length) {
        $("results").textContent = "見つかりませんでした。ローマ字か英語で入力してください（例: Sapporo）";
        return;
      }
      for (var i = 0; i < list.length; i++) {
        (function (r) {
          var b = document.createElement("button");
          b.type = "button";
          b.textContent = r.name + (r.admin ? " / " + r.admin : "") + (r.country ? " / " + r.country : "");
          b.addEventListener("click", function () {
            pendingLocation = { configured: true, name: r.name, latitude: r.latitude, longitude: r.longitude, timezone: r.timezone };
            $("results").innerHTML = "";
            $("q").value = "";
            renderPlace();
            updateDirty();
          });
          $("results").appendChild(b);
        })(list[i]);
      }
    }).catch(function (e) { $("results").textContent = "エラー: " + e.message; });
  });

  // ---------------------------------------------------------------- その場で効く操作

  function renderWallpaper() {
    var on = !!(config.wallpaper && config.wallpaper.imageSetAt > 0);
    $("wallpaperClear").disabled = !on;
    setStatus("wallpaperStatus", on ? "背景画像を表示中" : "背景画像なし");
  }

  /** 画像はそのまま送り、縮小と向きの補正はタブレット側で行う。 */
  $("wallpaperFile").addEventListener("change", function () {
    var file = this.files && this.files[0];
    var input = this;
    if (!file) return;
    setStatus("wallpaperStatus", "送信中…");
    fetch("/api/wallpaper", { method: "POST", cache: "no-store", headers: { "Content-Type": file.type || "application/octet-stream" }, body: file })
      .then(function (res) {
        return res.text().then(function (t) {
          var data = t ? JSON.parse(t) : null;
          if (!res.ok) throw new Error((data && (data.detail || data.error)) || "HTTP " + res.status);
          return data;
        });
      })
      .then(function (updated) {
        config.wallpaper = updated.wallpaper;
        renderWallpaper();
        setStatus("wallpaperStatus", "背景画像を設定しました", "ok");
      })
      .catch(function (e) { setStatus("wallpaperStatus", "エラー: " + e.message, "err"); })
      .then(function () { input.value = ""; });
  });

  $("wallpaperClear").addEventListener("click", function () {
    setStatus("wallpaperStatus", "処理中…");
    api("/api/wallpaper/clear", { method: "POST", body: "{}" })
      .then(function (updated) {
        config.wallpaper = updated.wallpaper;
        renderWallpaper();
        setStatus("wallpaperStatus", "背景画像を外しました", "ok");
      })
      .catch(function (e) { setStatus("wallpaperStatus", "エラー: " + e.message, "err"); });
  });

  $("spotifyConnect").addEventListener("click", function () { window.location.href = "/api/spotify/start"; });

  $("spotifyDisconnect").addEventListener("click", function () {
    setStatus("spotifyLink", "解除中…");
    api("/api/spotify/disconnect", { method: "POST" })
      .then(function (updated) { config.spotify = updated.spotify; renderSpotify(); setStatus("spotifyLink", "連携を解除しました", "ok"); })
      .catch(function (e) { setStatus("spotifyLink", "エラー: " + e.message, "err"); });
  });

  $("savePin").addEventListener("click", function () {
    setStatus("lanStatus", "設定中…");
    api("/api/lan", { method: "POST", body: JSON.stringify({ pin: $("pin").value }) })
      .then(function (updated) {
        config.lan = updated.lan;
        $("pin").value = "";
        renderLan();
        setStatus("lanStatus", "PIN を設定しました（既存のログインは無効化されます）", "ok");
      })
      .catch(function (e) { setStatus("lanStatus", "エラー: " + e.message, "err"); });
  });

  $("lanToggle").addEventListener("click", function () {
    var next = !config.lan.enabled;
    if (next && !confirm("LAN 公開を有効にします。信頼できる家庭内 LAN でのみ使用してください。続けますか？")) return;
    api("/api/lan", { method: "POST", body: JSON.stringify({ enabled: next }) })
      .then(function (updated) {
        config.lan = updated.lan;
        renderLan();
        // 待受の張り替え（約 0.3 秒後）が終わってから、待受アドレスを読み直す
        setTimeout(function () { loadDevice().catch(function () {}); }, 1500);
      })
      .catch(function (e) { setStatus("lanStatus", "エラー: " + e.message, "err"); });
  });

  $("launcherToggle").addEventListener("click", function () {
    api("/api/device", { method: "POST", body: JSON.stringify({ launcherHomeEnabled: !device.launcherHomeEnabled }) })
      .then(function (updated) { device = updated; renderDevice(); })
      .catch(function (e) { setStatus("launcherStatus", "エラー: " + e.message, "err"); });
  });

  $("refreshNow").addEventListener("click", function () {
    setStatus("refreshStatus", "取得中…");
    api("/api/refresh", { method: "POST", body: "{}" })
      .then(function () { setStatus("refreshStatus", "取得しました", "ok"); loadDisasterArea(); })
      .catch(function (e) { setStatus("refreshStatus", "エラー: " + e.message, "err"); });
  });

  // ---------------------------------------------------------------- 起動

  showPane(location.hash.replace("#", "") || "general");

  api("/api/settings")
    .then(function (c) {
      config = c;
      render();
      return Promise.all([loadDevice(), loadDisasterArea(), loadRailways().catch(function () {})]);
    })
    .catch(function (e) { $("access").textContent = "読み込みエラー: " + e.message; });
})();
