/*
 * 設定画面。USB(adb forward)経由の localhost からは認証なしで開ける。
 * LAN から開いた場合は先に PIN でログインしている必要がある。
 *
 * 画面は「左のメニューで項目を選び、右にその項目の設定だけを出す」構造。
 * ダッシュボードのカードは 1 枚 1 項目として並べ、そのカードに効く設定を同じ面に置く。
 *
 * カードの表示・非表示は config.display の show* に入っており、保存は /api/settings の
 * display をまるごと置き換える形になる。そのため、どの面の「保存」を押しても
 * 画面上のすべての [data-w] チェックボックスから display を組み立て直す
 * （面ごとに部分更新すると、別の面で変えたチェックが保存のたびに巻き戻る）。
 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var config = null;
  var device = null;

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

  // ---------------------------------------------------------------- メニュー

  /*
   * 選んだ面だけを出す。選択は location.hash に残す。
   * 歯車から開く iframe は閉じるたびに読み直されるので、
   * hash が無ければ常に先頭の面から始まる。
   */
  function showPane(name) {
    var items = document.querySelectorAll(".nav-item");
    var panes = document.querySelectorAll(".pane");
    var found = false;
    var i;

    for (i = 0; i < panes.length; i++) {
      var on = panes[i].getAttribute("data-pane") === name;
      if (on) found = true;
      if (on) panes[i].classList.add("active"); else panes[i].classList.remove("active");
    }
    if (!found) return showPane("general");

    for (i = 0; i < items.length; i++) {
      var sel = items[i].getAttribute("data-pane") === name;
      if (sel) items[i].classList.add("active"); else items[i].classList.remove("active");
    }
    // 面を切り替えたら先頭から読ませる（前の面のスクロール位置が残ると迷う）
    $("panes").scrollTop = 0;
    if (location.hash !== "#" + name) location.hash = name;
  }

  (function bindNav() {
    var items = document.querySelectorAll(".nav-item");
    for (var i = 0; i < items.length; i++) {
      items[i].addEventListener("click", function () {
        showPane(this.getAttribute("data-pane"));
      });
    }
    window.addEventListener("hashchange", function () {
      showPane(location.hash.replace("#", "") || "general");
    });
  })();

  // ---------------------------------------------------------------- 描画

  /** 画面上のすべての表示トグルを集めて display を組み立て直す。 */
  function displayPatch() {
    var display = {};
    for (var k in config.display) display[k] = config.display[k];

    display.layout = $("layout").value;
    display.accent = $("accent").value;
    display.burnInShiftEnabled = $("burnIn").checked;
    display.normalBrightness = Number($("normalBrightness").value) / 100;
    display.idleDimEnabled = $("idleDimEnabled").checked;
    display.idleDimAfterSeconds = Number($("idleDimAfter").value);
    display.idleDimBrightness = Number($("idleDimBrightness").value) / 100;

    display.clockAlign = $("clockAlign").value;
    display.clockDateFormat = $("clockDateFormat").value;
    display.hourlyMode = $("hourlyMode").value;

    /*
     * data-w の付いたチェックボックスはすべて display の真偽値。
     * カードの表示トグルのほか、台風・噴火・強震モニタ・地球・Spotify の
     * 各スイッチもこれで拾うので、項目を足すときは HTML 側に data-w を書くだけでよい。
     * メニュー横の点も data-w を持つため、入力要素に絞ってから読む。
     */
    var boxes = document.querySelectorAll("input[data-w]");
    for (var i = 0; i < boxes.length; i++) {
      display[boxes[i].getAttribute("data-w")] = boxes[i].checked;
    }

    // 天気の項目は並び順も意味を持つ（3 列で左上から詰まる）ので、
    // チェックボックスが置かれている順のまま配列にする。
    var wx = document.querySelectorAll("input[data-wx]");
    var fields = [];
    for (var j = 0; j < wx.length; j++) {
      if (wx[j].checked) fields.push(wx[j].getAttribute("data-wx"));
    }
    display.weatherFields = fields;

    return display;
  }

  /** 通知は display とは別の入れ物なので、ここで組み立てる。 */
  function notificationsPatch() {
    var n = {};
    for (var k in (config.notifications || {})) n[k] = config.notifications[k];
    n.disasterSound = $("disasterSound").checked;
    n.chargingSound = $("chargingSound").checked;
    n.volume = Number($("noticeVolume").value) / 100;
    return n;
  }

  function unitsPatch() {
    var units = {};
    for (var u in config.units) units[u] = config.units[u];
    units.clock24h = $("clock24").value === "true";
    units.temperature = $("tempUnit").value;
    units.showSeconds = $("showSeconds").checked;
    return units;
  }

  /** メニュー横の点を、いまの表示状態に合わせる。 */
  function renderDots() {
    var dots = document.querySelectorAll(".dot[data-w]");
    for (var i = 0; i < dots.length; i++) {
      var on = config.display[dots[i].getAttribute("data-w")] !== false;
      if (on) dots[i].classList.remove("off"); else dots[i].classList.add("off");
    }
  }

  function render() {
    var d = config.display, u = config.units;

    // 全体・画面
    $("layout").value = d.layout;
    $("accent").value = d.accent;
    $("burnIn").checked = d.burnInShiftEnabled;
    $("normalBrightness").value = Math.round((d.normalBrightness == null ? 1 : d.normalBrightness) * 100);
    $("idleDimEnabled").checked = d.idleDimEnabled !== false;
    $("idleDimAfter").value = d.idleDimAfterSeconds;
    $("idleDimBrightness").value = Math.round(d.idleDimBrightness * 100);

    // 場所
    $("locNow").textContent = "現在の設定地点: " + config.location.name + "（" + config.location.timezone + "）";
    $("weatherPlace").textContent = config.location.name;

    // 時刻・天気
    $("clock24").value = String(u.clock24h);
    $("showSeconds").checked = u.showSeconds;
    $("tempUnit").value = u.temperature;
    $("clockAlign").value = d.clockAlign || "left";
    $("clockDateFormat").value = d.clockDateFormat || "ja";
    $("hourlyMode").value = d.hourlyMode || "both";

    // 天気の項目。未設定の古い config では全項目が選ばれている扱いにする。
    var want = d.weatherFields || null;
    var wx = document.querySelectorAll("input[data-wx]");
    for (var w = 0; w < wx.length; w++) {
      var key = wx[w].getAttribute("data-wx");
      wx[w].checked = want ? want.indexOf(key) >= 0 : true;
    }

    // 各カードの表示トグル
    var boxes = document.querySelectorAll("input[data-w]");
    for (var i = 0; i < boxes.length; i++) {
      boxes[i].checked = d[boxes[i].getAttribute("data-w")] !== false;
    }
    renderDots();

    // 防災
    $("disasterEnabled").checked = config.disaster.enabled;
    $("minIntensity").value = config.disaster.minIntensity;

    // ニュース
    $("feedEnabled").checked = config.feed.enabled;
    $("feedUrls").value = (config.feed.urls || []).join("\n");
    $("feedMax").value = config.feed.maxItems;

    // LINE メモ
    $("memoEnabled").checked = config.memo.enabled;
    $("memoEndpoint").value = config.memo.endpoint;
    $("memoInterval").value = Math.round(config.memo.pollIntervalMs / 1000);
    $("memoToken").placeholder = config.memo.tokenSet
      ? "設定済み（変更する場合のみ入力）"
      : "未設定 — Worker の DEVICE_TOKEN と同じ値";

    // Spotify
    var sp = config.spotify || {};
    $("spotifyEnabled").checked = !!sp.enabled;
    $("spotifyClientId").value = sp.clientId || "";
    $("spotifyConnect").disabled = !sp.clientId;
    $("spotifyDisconnect").disabled = !sp.connected;
    setStatus("spotifyLink", sp.connected
      ? "連携済み"
      : (sp.clientId ? "未連携 —「Spotify と連携」を押してください" : "Client ID を保存すると連携できます"));

    // 通知。古い config には無いので既定値で補う。
    var n = config.notifications || {};
    $("disasterSound").checked = n.disasterSound !== false;
    $("chargingSound").checked = n.chargingSound !== false;
    $("noticeVolume").value = Math.round((n.volume == null ? 0.7 : n.volume) * 100);

    // ネットワーク
    var lanOn = config.lan.enabled;
    $("lanToggle").textContent = lanOn ? "LAN 公開を無効にする" : "LAN 公開を有効にする";
    setStatus("lanStatus", lanOn
      ? "公開中 — 他端末から http://<この端末のIP>:8080/settings で PIN ログイン"
      : (config.lan.pinSet ? "loopback のみ待受（PIN 設定済み）" : "loopback のみ待受（PIN 未設定）"));

    // つまみの数値は最後にまとめて書く。
    // 値を入れる前に呼ぶと、その時点で未設定のつまみが HTML の初期値のまま表示される。
    updateRangeLabels();
  }

  function updateRangeLabels() {
    $("normalBrightnessValue").textContent = $("normalBrightness").value + "%";
    $("idleDimBrightnessValue").textContent = $("idleDimBrightness").value + "%";
    $("noticeVolumeValue").textContent = $("noticeVolume").value + "%";
  }
  $("normalBrightness").addEventListener("input", updateRangeLabels);
  $("idleDimBrightness").addEventListener("input", updateRangeLabels);
  $("noticeVolume").addEventListener("input", updateRangeLabels);

  function renderDevice() {
    var on = device.launcherHomeEnabled;
    $("launcherToggle").textContent = on ? "ホームアプリ登録を解除" : "ホームアプリとして登録";
    setStatus("launcherStatus", on
      ? "登録済み — 端末の既定ホームアプリに Walldash を選べます"
      : "未登録 — 再起動後は手動でアプリを開く必要があります");
    $("access").textContent =
      "待受 " + device.boundHost + ":" + device.port + " ／ 有効セッション " + device.activeSessions;
  }

  /**
   * 警報・注意報の地域は天気の地点から端末が自動で決めるので、選ばせずに表示だけする。
   * 決まった結果は /api/state の disaster に載っている。
   */
  function loadDisasterArea() {
    return api("/api/state").then(function (s) {
      var d = (s && s.disaster) || {};
      var text;
      if (!config.disaster.enabled) text = "防災情報の取得が無効です";
      else if (!d.available) text = "まだ取得できていません";
      else if (!d.areaName) text = "天気の地点から市町村を決められません。「場所」で国内の地点を選んでください";
      else text = [d.officeName, d.areaName].filter(Boolean).join(" ");
      $("disasterArea").textContent = text;
    }).catch(function () {
      $("disasterArea").textContent = "取得できません";
    });
  }

  /** 地点や取得の有無を変えた直後は、定期取得を待たずに決め直させてから表示する。 */
  function refreshDisasterArea() {
    $("disasterArea").textContent = "確認中…";
    return api("/api/refresh", { method: "POST" })
      .catch(function () { /* 取得に失敗しても、いま決まっている地域は出せる */ })
      .then(loadDisasterArea);
  }

  // ---------------------------------------------------------------- 保存

  function patch(body, statusId, okMessage) {
    setStatus(statusId, "保存中…");
    return api("/api/settings", { method: "POST", body: JSON.stringify(body) })
      .then(function (updated) {
        config = updated;
        render();
        setStatus(statusId, okMessage || "保存しました", "ok");
      })
      .catch(function (e) { setStatus(statusId, "エラー: " + e.message, "err"); });
  }

  /**
   * 表示まわりだけを保存する。
   * カードの面はどれも「表示するかどうか」を持つので、固有の設定が無い面でもこれを使う。
   * [extra] を渡すと disaster / feed などを同じ 1 回の POST に混ぜられる。
   */
  function saveDisplay(statusId, extra) {
    var body = { display: displayPatch(), units: unitsPatch() };
    for (var k in (extra || {})) body[k] = extra[k];
    return patch(body, statusId);
  }

  /** 固有の設定を持たない面（表示トグルだけの面）の保存ボタン。 */
  function bindSimpleSave(buttonId, statusId) {
    $(buttonId).addEventListener("click", function () { saveDisplay(statusId); });
  }

  bindSimpleSave("saveGeneral", "generalStatus");
  bindSimpleSave("saveScreen", "screenStatus");
  bindSimpleSave("saveClock", "clockStatus");
  bindSimpleSave("saveWeather", "weatherStatus");
  bindSimpleSave("saveHourly", "hourlyStatus");
  bindSimpleSave("saveDaily", "dailyStatus");
  bindSimpleSave("saveWifi", "wifiStatus");
  bindSimpleSave("saveStats", "statsStatus");
  bindSimpleSave("saveTimer", "timerStatus");
  bindSimpleSave("saveWord", "wordStatus");
  bindSimpleSave("saveHamster", "hamsterStatus");

  $("saveNotify").addEventListener("click", function () {
    patch({ notifications: notificationsPatch() }, "notifyStatus");
  });

  $("saveDisaster").addEventListener("click", function () {
    saveDisplay("disasterStatus", {
      disaster: {
        enabled: $("disasterEnabled").checked,
        minIntensity: $("minIntensity").value
      }
    }).then(refreshDisasterArea);
  });

  $("saveFeed").addEventListener("click", function () {
    var urls = $("feedUrls").value.split("\n");
    var clean = [];
    for (var i = 0; i < urls.length; i++) {
      var u = urls[i].trim();
      if (u) clean.push(u);
    }
    saveDisplay("feedStatus", {
      feed: { enabled: $("feedEnabled").checked, urls: clean, maxItems: Number($("feedMax").value) }
    });
  });

  /*
   * メモと Spotify は専用の入口を持つ（トークンと更新用トークンを /api/settings の
   * 経路に通さないため）。表示トグルは display 側なので、2 本の POST が要る。
   * 表示を先に保存してから固有の設定を送り、最後の応答で画面を描き直す。
   */
  $("saveMemo").addEventListener("click", function () {
    var body = {
      enabled: $("memoEnabled").checked,
      endpoint: $("memoEndpoint").value.trim(),
      pollIntervalMs: Number($("memoInterval").value) * 1000
    };
    // 空のままなら既存のトークンを変更しない
    var token = $("memoToken").value;
    if (token) body.token = token;

    setStatus("memoStatus", "保存中…");
    api("/api/settings", { method: "POST", body: JSON.stringify({ display: displayPatch(), units: unitsPatch() }) })
      .then(function () { return api("/api/memo", { method: "POST", body: JSON.stringify(body) }); })
      .then(function (updated) {
        config = updated;
        $("memoToken").value = "";
        render();
        setStatus("memoStatus", "保存しました", "ok");
      })
      .catch(function (e) { setStatus("memoStatus", "エラー: " + e.message, "err"); });
  });

  $("saveSpotify").addEventListener("click", function () {
    setStatus("spotifyStatus", "保存中…");
    api("/api/settings", { method: "POST", body: JSON.stringify({ display: displayPatch(), units: unitsPatch() }) })
      .then(function () {
        return api("/api/spotify", {
          method: "POST",
          body: JSON.stringify({
            enabled: $("spotifyEnabled").checked,
            clientId: $("spotifyClientId").value.trim()
          })
        });
      })
      .then(function (updated) {
        config = updated;
        render();
        setStatus("spotifyStatus", "保存しました", "ok");
      })
      .catch(function (e) { setStatus("spotifyStatus", "エラー: " + e.message, "err"); });
  });

  /*
   * 認可画面は枠内には出せない（Spotify が frame-ancestors で拒む）。
   * タブレットの歯車から開いているときは iframe の中なので、アプリ内ブラウザに投げる。
   * PC のブラウザから開いているときはこのタブをそのまま移動させる。
   */
  $("spotifyConnect").addEventListener("click", function () {
    var start = "/api/spotify/start";
    if (window.top !== window.self) {
      window.top.location.href =
        "walldash://browser?url=" + encodeURIComponent("http://127.0.0.1:8080" + start);
    } else {
      window.location.href = start;
    }
  });

  $("spotifyDisconnect").addEventListener("click", function () {
    setStatus("spotifyLink", "解除中…");
    api("/api/spotify/disconnect", { method: "POST" })
      .then(function (updated) {
        config = updated;
        render();
        setStatus("spotifyLink", "連携を解除しました", "ok");
      })
      .catch(function (e) { setStatus("spotifyLink", "エラー: " + e.message, "err"); });
  });

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
            patch({
              location: {
                configured: true, name: r.name, latitude: r.latitude,
                longitude: r.longitude, timezone: r.timezone
              }
            }, "placeStatus", "地点を " + r.name + " に変更しました").then(refreshDisasterArea);
            $("results").innerHTML = "";
            $("q").value = "";
          });
          $("results").appendChild(b);
        })(list[i]);
      }
    }).catch(function (e) { $("results").textContent = "エラー: " + e.message; });
  });

  // ---------------------------------------------------------------- 端末

  $("savePin").addEventListener("click", function () {
    setStatus("lanStatus", "設定中…");
    api("/api/lan", { method: "POST", body: JSON.stringify({ pin: $("pin").value }) })
      .then(function (updated) {
        config = updated; $("pin").value = ""; render();
        setStatus("lanStatus", "PIN を設定しました（既存のログインは無効化されます）", "ok");
      })
      .catch(function (e) { setStatus("lanStatus", "エラー: " + e.message, "err"); });
  });

  $("lanToggle").addEventListener("click", function () {
    var next = !config.lan.enabled;
    if (next && !confirm("LAN 公開を有効にします。信頼できる家庭内 LAN でのみ使用してください。続けますか？")) return;
    api("/api/lan", { method: "POST", body: JSON.stringify({ enabled: next }) })
      .then(function (updated) {
        config = updated; render();
        setStatus("lanStatus", $("lanStatus").textContent + "（待受を切り替えるためサーバーを再起動しました）");
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
      .then(function () { setStatus("refreshStatus", "取得しました", "ok"); })
      .catch(function (e) { setStatus("refreshStatus", "エラー: " + e.message, "err"); });
  });

  // ---------------------------------------------------------------- 起動

  showPane(location.hash.replace("#", "") || "general");

  api("/api/settings")
    .then(function (c) {
      config = c;
      render();
      return Promise.all([
        api("/api/device").then(function (d) { device = d; renderDevice(); }),
        loadDisasterArea()
      ]);
    })
    .catch(function (e) { $("access").textContent = "読み込みエラー: " + e.message; });
})();
