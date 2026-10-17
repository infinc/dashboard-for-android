/*
 * Web の設定画面の「自作カード」（アプリの WidgetsPane.kt と同じ中身）。
 *
 * 4 枚の下書きをここで持ち、settings.js の collect() が DashWidgets.collect() を「全て保存」の widgets に入れる。
 * 面の入力欄はここで組み立てる（文は L() で書くので、i18n-en.js の対訳は要らない）。
 * 外から送る口（トークン）は /api/widgets/config でその場で保存し、「試しに取得」は /api/widgets/test。
 */
(function () {
  "use strict";

  var IDS = ["w1", "w2", "w3", "w4"];
  var INTERVALS = [1, 2, 5, 10, 15, 30, 60, 180, 360, 1440];
  var drafts = [];
  var saved = [];
  var headerSet = [];
  var templates = [];
  var tones = [];
  var memoTone = "";
  var pushEnabled = false;
  var pushInfo = null;
  var values = {};
  var selected = 0;

  function $(id) { return document.getElementById(id); }

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

  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    for (var k in (attrs || {})) {
      if (k === "text") e.textContent = attrs[k];
      else if (k === "className") e.className = attrs[k];
      else e.setAttribute(k, attrs[k]);
    }
    (children || []).forEach(function (c) { if (c) e.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return e;
  }

  function plain(v) { return v == null ? "" : String(v); }
  function num(s) {
    var t = String(s == null ? "" : s).trim().replace(/,/g, "").replace("−", "-");
    if (t === "" || isNaN(Number(t))) return null;
    return Number(t);
  }
  function defaultLabel(id) { return L("自作 ", "Custom ") + id.replace("w", ""); }

  /** 「名前 URL」の行（アプリの WidgetSanitize.parseTargets と同じ）。 */
  function parseTargets(text) {
    return String(text || "").split("\n").map(function (l) { return l.trim(); }).filter(Boolean).map(function (line) {
      var m = /(https?|tcp):\/\//i.exec(line);
      if (!m) return { name: line, url: "" };
      return { name: line.slice(0, m.index).trim(), url: line.slice(m.index).trim() };
    });
  }
  function targetLines(targets) {
    return (targets || []).map(function (t) { return t.name ? t.name + " " + t.url : t.url; }).join("\n");
  }

  /** 保存された設定 → 下書き（数は文字で持つ）。 */
  function toDraft(d) {
    return {
      id: d.id, title: d.title || "", source: d.source || "http", style: d.style || "number",
      url: d.url || "", headerName: d.headerName || "", headerValue: "", clearHeader: false,
      path: d.path || "", labelKey: d.labelKey || "", valueKey: d.valueKey || "",
      unitPath: d.unitPath || "", unit: d.unit || "", decimals: d.decimals == null ? -1 : d.decimals,
      scale: plain(d.scale == null ? 1 : d.scale), min: plain(d.min == null ? 0 : d.min), max: plain(d.max == null ? 100 : d.max),
      warnAt: plain(d.warnAt), alertAt: plain(d.alertAt), below: !!d.below, notify: !!d.notify, tone: d.tone || "",
      intervalMin: d.intervalMin || 5, targets: targetLines(d.targets)
    };
  }

  /** 下書き → 送る形（WidgetDef）。ヘッダーの値は入れたときだけ（空なら保存済みのまま、消すときは空文字）。 */
  function toDef(w) {
    var def = {
      id: w.id, title: w.title.trim(), source: w.source, style: w.style, url: w.url.trim(),
      headerName: w.headerName.trim(), path: w.path.trim(), labelKey: w.labelKey.trim(), valueKey: w.valueKey.trim(),
      unitPath: w.unitPath.trim(), unit: w.unit.trim(), decimals: Number(w.decimals),
      scale: num(w.scale) == null ? 1 : num(w.scale), min: num(w.min) == null ? 0 : num(w.min), max: num(w.max) == null ? 100 : num(w.max),
      warnAt: num(w.warnAt), alertAt: num(w.alertAt), below: w.below, notify: w.notify, tone: w.tone,
      intervalMin: Number(w.intervalMin), targets: parseTargets(w.targets)
    };
    if (w.clearHeader) def.headerValue = "";
    else if (w.headerValue) def.headerValue = w.headerValue;
    return def;
  }

  // ---------------------------------------------------------------- 値の場所（アプリの JsonPick と同じ書き方）

  var IDENT = /^[A-Za-z_À-￿][A-Za-z0-9_\-À-￿]*$/;
  function joinKey(parent, key) {
    if (IDENT.test(key)) return parent ? parent + "." + key : key;
    return parent + "[\"" + key.replace(/\\/g, "\\\\").replace(/"/g, "\\\"") + "\"]";
  }
  function joinIndex(parent, i) { return parent + "[" + i + "]"; }

  // ---------------------------------------------------------------- 札・表示の点

  function shown(display, i) { return !!(display && display["showWidget" + (i + 1)]); }

  function displayNow() {
    var d = {};
    for (var i = 0; i < 4; i++) {
      var box = document.querySelector("input[data-w=showWidget" + (i + 1) + "]");
      d["showWidget" + (i + 1)] = box ? box.checked : false;
    }
    return d;
  }

  function renderTabs() {
    var tabs = $("widgetTabs");
    if (!tabs) return;
    tabs.innerHTML = "";
    var disp = displayNow();
    drafts.forEach(function (w, i) {
      var dot = el("i", { className: shown(disp, i) ? "on" : "" });
      var b = el("button", { type: "button", className: i === selected ? "active" : "" }, [dot, defaultLabel(w.id) + (w.title ? L("：", ": ") + w.title : "")]);
      b.addEventListener("click", function () { select(i); });
      tabs.appendChild(b);
    });
    var any = [0, 1, 2, 3].some(function (i) { return shown(disp, i); });
    var navDot = $("widgetsDot");
    if (navDot) navDot.classList.toggle("off", !any);
    var labels = document.querySelectorAll("[data-wg-show]");
    for (var k = 0; k < labels.length; k++) labels[k].hidden = labels[k].getAttribute("data-wg-show") !== IDS[selected];
  }

  function select(i) {
    selected = i;
    renderTabs();
    renderForm();
  }

  // ---------------------------------------------------------------- 入力欄

  function field(label, hint, controls, attrs) {
    var f = el("div", Object.assign({ className: "field" }, attrs || {}));
    if (label) f.appendChild(el("label", { text: label }));
    (controls || []).forEach(function (c) { f.appendChild(c); });
    if (hint) f.appendChild(el("div", { className: "hint", text: hint }));
    return f;
  }

  function input(key, placeholder, type) {
    var w = drafts[selected];
    var e = el("input", { type: type || "text", autocomplete: type === "password" ? "new-password" : "off", placeholder: placeholder || "" });
    e.value = w[key];
    e.addEventListener("input", function () {
      w[key] = e.value;
      if (key === "headerValue") w.clearHeader = false;
      if (key === "title") renderTabs();
      if (key === "path") checkPath();
    });
    return e;
  }

  function select_(key, options, onChange) {
    var w = drafts[selected];
    var e = el("select");
    options.forEach(function (o) {
      var opt = el("option", { value: String(o[0]), text: o[1] });
      e.appendChild(opt);
    });
    e.value = String(w[key]);
    e.addEventListener("change", function () {
      w[key] = typeof w[key] === "number" ? Number(e.value) : e.value;
      if (onChange) onChange();
    });
    return e;
  }

  function check(key, label) {
    var w = drafts[selected];
    var box = el("input", { type: "checkbox" });
    box.checked = !!w[key];
    box.addEventListener("change", function () { w[key] = box.checked; applyVisibility(); });
    return el("label", { className: "toggle" }, [box, " " + label]);
  }

  function intervalOptions() {
    return INTERVALS.map(function (m) {
      return [m, m < 60 ? L(m + " 分ごと", "Every " + m + " min") : m === 1440 ? L("1 日ごと", "Once a day") : L((m / 60) + " 時間ごと", "Every " + (m / 60) + " h")];
    });
  }

  function renderForm() {
    var box = $("widgetForm");
    if (!box || !drafts.length) return;
    box.innerHTML = "";
    var w = drafts[selected];

    // ひな形
    var tpl = el("select");
    tpl.appendChild(el("option", { value: "", text: L("選んでください…", "Choose…") }));
    templates.forEach(function (t) { tpl.appendChild(el("option", { value: t.id, text: t.label })); });
    tpl.addEventListener("change", function () {
      var t = templates.filter(function (x) { return x.id === tpl.value; })[0];
      if (!t) return;
      var keepTitle = w.title;
      var keepHeader = w.headerValue;
      var next = toDraft(Object.assign({}, t.def, { id: w.id }));
      next.title = keepTitle || next.title;
      next.headerValue = keepHeader;
      drafts[selected] = next;
      renderTabs();
      renderForm();
      document.dispatchEvent(new Event("change"));
    });
    box.appendChild(field(L("ひな形", "Template"), L("選ぶと、下の欄をその例で埋めます（見出しが空なら見出しも）。あとから自由に直せます。", "Fills the fields below with an example (and the title if it's empty). You can change anything afterwards."), [tpl]));

    box.appendChild(field(L("見出し", "Title"), L("カードの左上に出す名前です（30 文字まで）。空なら「" + defaultLabel(w.id) + "」。", "The name at the top left of the card (up to 30 characters). Empty means \"" + defaultLabel(w.id) + "\"."), [input("title", defaultLabel(w.id))]));

    box.appendChild(field(L("取得のしかた", "Source"), null, [select_("source", [
      ["http", L("URL から取る（JSON）", "Fetch from a URL (JSON)")],
      ["push", L("外から送ってもらう", "Pushed from outside")],
      ["monitor", L("死活監視（動いているか）", "Uptime monitor")]
    ], function () { renderForm(); })]));

    // URL から取る
    box.appendChild(field("URL", L(
      "JSON を返す URL です。外のサイトは https:// にしてください。http:// は家の中（LAN）の機器（192.168.x.x・homeassistant.local など）だけに使えます。{lat}・{lon} は「場所」の緯度・経度に、{lang} は表示の言語に置き換えます。",
      "A URL that returns JSON. Use https:// for outside sites; plain http:// works only for devices on your LAN (192.168.x.x, homeassistant.local…). {lat} and {lon} become your location's coordinates and {lang} the display language."
    ), [input("url", "https://…")], { "data-src": "http" }));

    var hasHeader = headerSet[selected] && !w.clearHeader;
    var clear = el("button", { type: "button", className: "ghost", text: L("保存したヘッダーの値を消す", "Clear the saved header value") });
    clear.hidden = !hasHeader;
    clear.addEventListener("click", function () { w.clearHeader = true; w.headerValue = ""; renderForm(); document.dispatchEvent(new Event("change")); });
    box.appendChild(field(L("ヘッダー（任意）", "Header (optional)"), L(
      "トークンの要る API のためのヘッダー 1 つです（Home Assistant なら名前「Authorization」、値「Bearer 長期アクセストークン」）。値は保存したあと表示しません。URL のホストを変えると値は消えます（入れ直してください）。",
      "One header for APIs that need a token (for Home Assistant: name \"Authorization\", value \"Bearer <long-lived token>\"). The saved value is never shown. Changing the URL's host clears it (enter it again)."
    ), [
      el("div", { className: "wg-row" }, [
        input("headerName", L("名前（例: Authorization）", "Name (e.g. Authorization)")),
        input("headerValue", w.clearHeader ? L("保存すると消します", "Cleared when saved") : hasHeader ? L("設定済み（変更する場合のみ入力）", "Set (enter only to change)") : L("値", "Value"), "password")
      ]),
      clear
    ], { "data-src": "http" }));

    // 死活監視
    var targets = el("textarea", { placeholder: "NAS http://192.168.1.10:5000\nSSH tcp://192.168.1.20:22\nGoogle https://www.google.com" });
    targets.value = w.targets;
    targets.addEventListener("input", function () { w.targets = targets.value; });
    box.appendChild(field(L("監視する先", "Targets"), L(
      "1 行に 1 つ、「名前 URL」の形で（名前は省けます。8 つまで）。https:// と LAN の http:// は応答（2xx・3xx は正常、4xx は注意、5xx と応答なしは警戒）を、tcp://ホスト:ポート はつながるかを見ます。",
      "One per line as \"name URL\" (the name is optional, up to 8). For https:// and LAN http:// the response is checked (2xx/3xx up, 4xx warning, 5xx or no response down); tcp://host:port checks that it connects."
    ), [targets], { "data-src": "monitor" }));

    box.appendChild(field(L("取りに行く間隔", "Interval"), L("カードを表示している間だけ取りに行きます。", "Fetched only while the card is shown."), [select_("intervalMin", intervalOptions())], { "data-src": "http monitor" }));

    // 外から送る
    box.appendChild(pushBlock(w));

    // 見せ方
    box.appendChild(field(L("見せ方", "Style"), null, [select_("style", [
      ["number", L("数値（大きな値と 24 時間の折れ線）", "Number (big value and 24 h line)")],
      ["gauge", L("メーター（最小〜最大の半円）", "Gauge (half circle from min to max)")],
      ["list", L("一覧（名前と値の行）", "List (rows of names and values)")],
      ["text", L("文字", "Text")]
    ], function () { applyVisibility(); })], { "data-src": "http push" }));

    box.appendChild(field(L("値の場所", "Value path"), L(
      "応答の中の値の場所です（例: state・data.temp・items[0].value・rates.JPY）。一覧の見せ方では配列（か物）の場所です。下の「試しに取得」で値を押すと入ります。外から {\"value\": 23.4} の形で送るときは空でかまいません。",
      "Where the value is in the response (e.g. state, data.temp, items[0].value, rates.JPY). For the list style, the path to an array (or object). Tap a value after \"Try fetching\" below to fill it in. Leave it empty when pushing simple bodies like {\"value\": 23.4}."
    ), [input("path", L("例: data.temperature", "e.g. data.temperature")), el("div", { className: "status err", id: "wgPathStatus" })], { "data-src": "http push" }));

    box.appendChild(field(L("一覧の行（配列のとき）", "List rows (for arrays)"), L("各要素の中の、名前と値の場所です。空なら 1 つ目と 2 つ目の項目。", "Paths inside each element for the name and the value. Empty means the first and second fields."), [
      el("div", { className: "wg-row" }, [input("labelKey", L("名前のキー（例: name）", "Name key (e.g. name)")), input("valueKey", L("値のキー（例: value）", "Value key (e.g. value)"))])
    ], { "data-src": "http push", "data-style": "list" }));

    var decimals = [[-1, L("小数の桁: 自動", "Decimals: auto")]].concat([0, 1, 2, 3, 4].map(function (n) { return [n, L("小数の桁: " + n, "Decimals: " + n)]; }));
    box.appendChild(field(L("単位と桁", "Unit and digits"), L(
      "単位の場所を入れると応答の単位を使います（Home Assistant の attributes.unit_of_measurement など）。倍率は取り出した値に掛けます（バイト → MB なら 0.000001）。",
      "With a unit path the response's own unit is used (e.g. Home Assistant's attributes.unit_of_measurement). The scale multiplies the value (bytes → MB: 0.000001)."
    ), [
      el("div", { className: "wg-row three" }, [input("unit", L("単位（例: ℃）", "Unit (e.g. °C)")), input("unitPath", L("単位の場所（任意）", "Unit path (optional)")), input("scale", L("倍率", "Scale"))]),
      select_("decimals", decimals)
    ], { "data-src": "http push", "data-style": "number gauge list" }));

    box.appendChild(field(L("メーターの範囲", "Gauge range"), null, [
      el("div", { className: "wg-row" }, [input("min", L("最小", "Min")), input("max", L("最大", "Max"))])
    ], { "data-src": "http push", "data-style": "gauge" }));

    box.appendChild(field(L("しきい値（任意）", "Thresholds (optional)"), L("越えると色が変わります（注意は琥珀、警戒は赤）。一覧では各行の値で決めます。", "Colors change when crossed (amber for warning, red for alert). In lists, each row is judged by its own value."), [
      el("div", { className: "wg-row" }, [input("warnAt", L("注意", "Warning")), input("alertAt", L("警戒", "Alert"))]),
      check("below", L("下回ったら（例: 湿度・電池）", "When it falls below (e.g. humidity, battery)"))
    ], { "data-src": "http push", "data-style": "number gauge list" }));

    var toneOptions = [["", L("音: LINE メモと同じ", "Sound: same as LINE memo")]].concat(tones.map(function (t) { return [t.value, t.label]; }));
    var preview = el("button", { type: "button", className: "ghost", text: L("試聴", "Preview") });
    preview.addEventListener("click", function () {
      api("/api/sound/preview?tone=" + encodeURIComponent(w.tone || memoTone) + "&volume=0.7", { method: "POST" }).catch(function () {});
    });
    var toneRow = el("div", { className: "row", "data-notify": "1" }, [select_("tone", toneOptions), preview]);
    toneRow.firstChild.className = "tone";
    box.appendChild(field(L("知らせる", "Notify"), L(
      "警戒に入ったとき・ふつうに戻ったとき（監視なら、どれかが応答しなくなったとき・すべて戻ったとき）に、バナーと音で知らせます。",
      "Shows a banner and plays a sound when it enters the alert level and when it returns to normal (for monitors: when something stops responding and when everything is back)."
    ), [check("notify", L("知らせる", "Notify")), toneRow], { "data-style": "number gauge list monitor" }));

    box.appendChild(testBlock(w));
    box.appendChild(el("div", { className: "status", id: "wgStatus" }));

    applyVisibility();
    checkPath();
    renderStatus();
  }

  /** 取得のしかた・見せ方に合わない欄を隠す。 */
  function applyVisibility() {
    var w = drafts[selected];
    var style = w.source === "monitor" ? "monitor" : w.style;
    var fields = document.querySelectorAll("#widgetForm [data-src], #widgetForm [data-style]");
    for (var i = 0; i < fields.length; i++) {
      var src = fields[i].getAttribute("data-src");
      var st = fields[i].getAttribute("data-style");
      var ok = (!src || src.split(" ").indexOf(w.source) >= 0) && (!st || st.split(" ").indexOf(style) >= 0);
      fields[i].hidden = !ok;
    }
    var toneRow = document.querySelector("#widgetForm [data-notify]");
    if (toneRow) toneRow.hidden = !w.notify;
  }

  /** 値の場所の書き方を確かめる（アプリの JsonPick.parse と同じ規則のうち、よくあるまちがいだけ）。 */
  function checkPath() {
    var st = $("wgPathStatus");
    if (!st) return;
    var p = drafts[selected].path.trim().replace(/^\$\.?/, "");
    var bad = /\.\.|\.$|^\.|\[(?!-?\d+\]|"(?:[^"\\]|\\.)*"\]|'(?:[^'\\]|\\.)*'\])/.test(p) || (p.split("[").length !== p.split("]").length);
    st.textContent = bad ? L("書き方が読めません（例: a.b[0].c、[\"点.の入ったキー\"]）", "Can't read this path (e.g. a.b[0].c, [\"key.with.dots\"])") : "";
  }

  // ---------------------------------------------------------------- 外から送る

  function pushBlock(w) {
    var wrap = el("div", { "data-src": "push" });
    var toggle = el("button", { type: "button", className: "ghost", text: "—" });
    var status = el("span", { className: "status" });
    var inner = el("div");
    wrap.appendChild(field(L("外から送る口（4 枚で共通・すぐに保存）", "Push endpoint (shared by all 4 cards, saved at once)"), L(
      "Home Assistant の自動化・家のセンサー・PC のスクリプトから、このカードへ値を送ります。トークンを 10 回まちがえた端末は 10 分受け付けません。",
      "Send values to this card from Home Assistant automations, home sensors or scripts on your PC. A device that sends a wrong token 10 times is refused for 10 minutes."
    ), [el("div", { className: "row" }, [toggle, status]), inner]));

    function draw() {
      var p = pushInfo || { enabled: pushEnabled };
      toggle.textContent = p.enabled ? L("受け付けをやめる", "Stop accepting") : L("外から送られた値を受け付ける", "Accept pushed values");
      status.textContent = p.enabled ? L("受け付けています", "Accepting") : L("受け付けていません", "Not accepting");
      status.className = "status" + (p.enabled ? " ok" : "");
      inner.innerHTML = "";
      if (!p.enabled || !pushInfo) return;
      var regen = el("button", { type: "button", className: "ghost", text: L("作り直す", "Regenerate") });
      regen.addEventListener("click", function () {
        if (!confirm(L("トークンを作り直します。送る側の設定も直す必要があります。続けますか？", "Regenerate the token? You'll need to update the sender too."))) return;
        change({ regenerate: true });
      });
      var url = (p.lanUrl || "http://" + L("<タブレットの IP>", "<tablet IP>") + ":8080/api/widgets/") + w.id;
      inner.appendChild(field(L("トークン", "Token"), L("送るときに Authorization: Bearer の後ろに付けます。", "Send it after Authorization: Bearer."), [el("div", { className: "row" }, [el("code", { text: p.token || "" }), regen])]));
      inner.appendChild(field(L("送り方", "How to send"), L(
        "本文は {\"value\": 数}・{\"text\": \"文字\"}・{\"items\": [{\"label\": \"名前\", \"value\": 値, \"level\": \"ok|warn|alert\"}]}（unit も付けられます）か、ただの数・文字。「値の場所」を入れておけば、Home Assistant の状態などをそのまま送れます。",
        "The body is {\"value\": number}, {\"text\": \"text\"} or {\"items\": [{\"label\": \"name\", \"value\": v, \"level\": \"ok|warn|alert\"}]} (unit is allowed too), or a bare number or text. With a value path set, you can send e.g. a Home Assistant state as is."
      ), [el("pre", { className: "wg-pre", text:
        "curl -X POST " + url + " \\\n" +
        "  -H 'Authorization: Bearer " + (p.token || "") + "' \\\n" +
        "  -H 'Content-Type: application/json' \\\n" +
        "  -d '{\"value\": 23.4}'" })]));
      if (!p.lanEnabled) inner.appendChild(el("div", { className: "notice", text: L("他の端末から送るには「ネットワーク」で LAN 公開を有効にしてください。", "To send from other devices, enable LAN access under \"Network\".") }));
    }

    function change(patch) {
      api("/api/widgets/config", { method: "POST", body: JSON.stringify(patch) })
        .then(function (p) { pushInfo = p; pushEnabled = p.enabled; draw(); renderStatus(); })
        .catch(function (e) { status.textContent = L("エラー: ", "Error: ") + e.message; status.className = "status err"; });
    }

    toggle.addEventListener("click", function () { change({ enabled: !(pushInfo ? pushInfo.enabled : pushEnabled) }); });
    draw();
    if (!pushInfo) api("/api/widgets/config").then(function (p) { pushInfo = p; draw(); }).catch(function () {});
    return wrap;
  }

  // ---------------------------------------------------------------- 試しに取得

  function testBlock(w) {
    var button = el("button", { type: "button", className: "ghost", text: L("試しに取得", "Try fetching") });
    var out = el("div");
    button.addEventListener("click", function () {
      button.disabled = true;
      button.textContent = L("取得中…", "Fetching…");
      out.innerHTML = "";
      var def = toDef(w);
      if (!def.headerValue) delete def.headerValue;
      api("/api/widgets/test", { method: "POST", body: JSON.stringify({ def: def }) })
        .then(function (r) { showResult(r, out); })
        .catch(function (e) { out.appendChild(el("div", { className: "status err", text: L("エラー: ", "Error: ") + e.message })); })
        .then(function () { button.disabled = false; button.textContent = L("試しに取得", "Try fetching"); });
    });
    return field(L("試しに取得", "Try fetching"), L(
      "保存していない下書きのまま 1 回だけ取りに行きます。下の木で値を押すと「値の場所」に入ります（一覧の見せ方なら配列を押します）。",
      "Fetches once with the unsaved draft. Tap a value in the tree below to set the value path (for the list style, tap an array)."
    ), [el("div", { className: "row" }, [button]), out], { "data-src": "http monitor" });
  }

  function fmt(n) {
    if (n == null) return "—";
    var a = Math.abs(n);
    var digits = a >= 1000 ? 0 : a >= 100 ? 1 : 2;
    return Number(n.toFixed(digits)).toLocaleString("en-US", { maximumFractionDigits: digits });
  }

  function showResult(r, out) {
    var w = drafts[selected];
    var d = r.data;
    var summary;
    if (!r.ok) summary = L("失敗: ", "Failed: ") + (r.error || "");
    else if (d && d.number != null) summary = L("取れました: ", "Got: ") + fmt(d.number) + (d.unit ? " " + d.unit : "");
    else if (d && d.text != null) summary = L("取れました: ", "Got: ") + d.text.slice(0, 80);
    else if (d && d.items && d.items.length) summary = L("取れました: " + d.items.length + " 行", "Got " + d.items.length + " rows");
    else summary = L("取れました", "OK");
    out.appendChild(el("div", { className: "status " + (r.ok ? "ok" : "err"), text: (r.status ? "HTTP " + r.status + " ・ " : "") + summary }));
    if (d && d.items && d.items.length && w.source === "monitor") {
      var list = el("div", { className: "wg-items" });
      d.items.forEach(function (it) { list.appendChild(el("div", { className: it.level ? "bad" : "", text: it.label + ": " + it.value })); });
      out.appendChild(list);
    }
    var root = null;
    try { root = r.raw ? JSON.parse(r.raw) : null; } catch (e) { root = null; }
    if (root === null && r.raw) {
      out.appendChild(el("pre", { className: "wg-pre", text: r.raw.slice(0, 1200) }));
      return;
    }
    if (root === null) return;
    var tree = el("div", { className: "wg-tree" });
    var count = 0;
    function add(v, key, path, depth) {
      if (count++ > 300) return;
      var container = v !== null && typeof v === "object";
      var pickable = w.style === "list" ? container || depth === 0 : !container;
      var preview = Array.isArray(v) ? "[" + v.length + "]" : container ? "{" + Object.keys(v).length + "}" : typeof v === "string" ? "\"" + v.slice(0, 60) + "\"" : String(v);
      var row = el("div", { className: "wg-node" + (pickable ? " pick" : "") + (pickable && path === w.path.trim() ? " chosen" : "") }, [el("b", { text: key }), el("span", { text: preview })]);
      row.style.paddingLeft = (10 + depth * 14) + "px";
      if (pickable) row.addEventListener("click", function () {
        w.path = path;
        var inputs = document.querySelectorAll("#widgetForm input");
        for (var i = 0; i < inputs.length; i++) if (inputs[i].placeholder === L("例: data.temperature", "e.g. data.temperature")) inputs[i].value = path;
        var chosen = tree.querySelectorAll(".chosen");
        for (var c = 0; c < chosen.length; c++) chosen[c].classList.remove("chosen");
        row.classList.add("chosen");
        checkPath();
        document.dispatchEvent(new Event("change"));
      });
      tree.appendChild(row);
      if (!container || depth >= 7) return;
      if (Array.isArray(v)) v.slice(0, 20).forEach(function (x, i) { add(x, "[" + i + "]", joinIndex(path, i), depth + 1); });
      else Object.keys(v).forEach(function (k) { add(v[k], k, joinKey(path, k), depth + 1); });
    }
    add(root, "$", "", 0);
    out.appendChild(tree);
  }

  // ---------------------------------------------------------------- いまの値

  function renderStatus() {
    var st = $("wgStatus");
    if (!st || !drafts.length) return;
    var def = saved[selected] || {};
    var v = values[IDS[selected]];
    var disp = displayNow();
    var text, kind = "";
    if (!shown(disp, selected)) text = L("カードを表示しているときだけ取得します", "Fetched only while the card is shown");
    else if (def.source === "push" && !pushEnabled) text = L("外から送る口が閉じています", "Pushing is turned off");
    else if (v && v.lastError) { text = L("エラー: ", "Error: ") + v.lastError; kind = "warn"; }
    else if (v && v.updatedAt) { text = L("取得できています", "Working") + (v.number != null ? L("（いまの値 ", " (now ") + fmt(v.number) + (v.unit ? " " + v.unit : "") + L("）", ")") : ""); kind = "ok"; }
    else text = def.source === "push" ? L("まだ値が届いていません", "No value has arrived yet") : L("まだ取得していません —「全て保存」のあと少し待ってください", "Not fetched yet — wait a moment after Save all");
    st.textContent = text;
    st.className = "status" + (kind ? " " + kind : "");
  }

  function loadValues() {
    return api("/api/state").then(function (s) {
      values = {};
      ((s.widgets && s.widgets.items) || []).forEach(function (d) { values[d.id] = d; });
      renderStatus();
    }).catch(function () {});
  }

  // カードの表示を切り替えたら札の点も直す（配置の検査で戻されたときも change が来る）
  document.addEventListener("change", function (e) {
    var t = e.target;
    if (t && t.getAttribute && /^showWidget/.test(t.getAttribute("data-w") || "")) setTimeout(renderTabs, 0);
  });

  // ---------------------------------------------------------------- settings.js から呼ぶ口

  window.DashWidgets = {
    /** 保存された設定で描き直す（読み込み・保存のあと）。 */
    render: function (config) {
      var w = config.widgets || {};
      saved = (w.items || []).slice();
      headerSet = (w.headerSet || []).slice();
      pushEnabled = !!w.pushEnabled;
      pushInfo = null;
      templates = (config.choices && config.choices.widgetTemplates) || [];
      tones = (config.choices && config.choices.tones) || [];
      memoTone = (config.notifications && config.notifications.memoTone) || "";
      drafts = IDS.map(function (id) {
        var d = saved.filter(function (x) { return x.id === id; })[0] || { id: id };
        return toDraft(d);
      });
      renderTabs();
      renderForm();
    },
    /** 「全て保存」で送る widgets。 */
    collect: function () { return { items: drafts.map(toDef) }; },
    /** 面を開いたとき。 */
    onShow: function () { renderTabs(); loadValues(); },
    /** カードの表示を切り替えたとき（札の点と、左のメニューの点）。 */
    refreshDots: renderTabs
  };
})();
