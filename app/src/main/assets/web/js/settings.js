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
    display.radarZoom = Number($("radarZoom").value);
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
    $("radarZoom").value = String(d.radarZoom || 8);

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
    baseline = JSON.stringify(collect());
    updateDirty();
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

  /*
   * カードを表示に切り替えたら、タブレットに「画面に収まるか」を確かめる（判定はアプリと同じ計算）。
   * 収まらないなら理由を出してチェックを戻す。確かめられないとき（通信の失敗）は止めず、保存時の検査に任せる。
   */
  (function bindCardChecks() {
    var boxes = document.querySelectorAll("input[data-card]");
    for (var i = 0; i < boxes.length; i++) {
      boxes[i].addEventListener("change", function () {
        var box = this;
        if (!box.checked) return;
        var after = collect().settings.display;
        box.checked = false;
        var before = collect().settings.display;
        box.checked = true;
        api("/api/layout/check", { method: "POST", body: JSON.stringify({ before: before, after: after }) })
          .then(function (r) {
            if (r.ok) return;
            box.checked = false;
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
