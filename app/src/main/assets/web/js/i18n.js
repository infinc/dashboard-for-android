/*
 * Web の設定画面（settings.html / login.html）の日本語と英語の切り替え。
 *
 * 表示の言語はタブレットの設定（display.language）で、サーバーが <html lang> に入れて返す。
 * 英語のときは、HTML に書いた日本語を EN_HTML / EN_TEXT の対訳に置き換える。
 * 置き換えの単位は「文字と、強調・リンク・コードなどの文中の要素だけを含む要素」の中身（innerHTML、空白を 1 つに詰めたもの）。
 * 入力欄などを含む要素は、直下の文字だけを EN_TEXT で置き換える。placeholder・title・aria-label・<option> も同じ。
 * 対訳に無い日本語はそのまま残る（ブラウザのコンソールで I18N.missing() を呼ぶと、対訳の無い文の一覧が出る）。
 *
 * settings.js が後から組み立てる文は L("日本語", "English") で書く（Kotlin の L と同じ）。
 */
(function () {
  "use strict";

  var lang = document.documentElement.lang === "en" ? "en" : "ja";
  window.LANG = lang;
  window.L = function (ja, en) { return lang === "en" ? en : ja; };

  var JP = /[぀-ヿ一-鿿！-｠]/;
  var INLINE = { A: 1, STRONG: 1, B: 1, EM: 1, I: 1, CODE: 1, BR: 1, SMALL: 1, KBD: 1, SPAN: 1, SUP: 1, SUB: 1 };
  var SKIP = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1 };

  function norm(s) { return s.replace(/\s+/g, " ").trim(); }

  function hasOwnJapanese(el) {
    for (var n = el.firstChild; n; n = n.nextSibling) if (n.nodeType === 3 && JP.test(n.nodeValue)) return true;
    return false;
  }

  /** 文中の要素だけを含み（入れ子も文中の要素だけ）、日本語の文字を含む要素なら、中身ごと置き換える単位。 */
  function isUnit(el) {
    if (!JP.test(el.textContent)) return false;
    var all = el.getElementsByTagName("*");
    for (var i = 0; i < all.length; i++) if (!INLINE[all[i].tagName] || all[i].id) return false;
    return true;
  }

  /** 置き換えの単位を集める。 */
  function collect(root, out) {
    for (var c = root.firstElementChild; c; c = c.nextElementSibling) {
      if (SKIP[c.tagName]) continue;
      if (c.tagName === "SELECT") {
        for (var o = 0; o < c.options.length; o++) if (JP.test(c.options[o].text)) out.push({ kind: "text", node: c.options[o] });
        continue;
      }
      if (c.tagName !== "OPTION" && isUnit(c)) {
        out.push({ kind: "html", node: c });
        continue;
      }
      if (hasOwnJapanese(c)) {
        for (var n = c.firstChild; n; n = n.nextSibling) if (n.nodeType === 3 && JP.test(n.nodeValue)) out.push({ kind: "node", node: n });
      }
      collect(c, out);
    }
    return out;
  }

  function attrs(root, out) {
    var els = root.querySelectorAll("[placeholder],[title],[aria-label]");
    for (var i = 0; i < els.length; i++) {
      ["placeholder", "title", "aria-label"].forEach(function (a) {
        var v = els[i].getAttribute(a);
        if (v && JP.test(v)) out.push({ kind: "attr", node: els[i], attr: a });
      });
    }
    return out;
  }

  function keyOf(u) {
    switch (u.kind) {
      case "html": return norm(u.node.innerHTML);
      case "text": return norm(u.node.text);
      case "node": return norm(u.node.nodeValue);
      case "attr": return norm(u.node.getAttribute(u.attr));
    }
  }

  /** 対訳の無い文（HTML に書いた日本語のうち、EN_HTML / EN_TEXT に無いもの）。日本語の画面で呼んでも調べられる。 */
  function missing() {
    var EN_HTML = window.EN_HTML || {}, EN_TEXT = window.EN_TEXT || {};
    var src = new XMLHttpRequest();
    src.open("GET", location.pathname === "/settings" || location.pathname === "/" ? "/static/settings.html" : location.pathname, false);
    src.send();
    var doc = new DOMParser().parseFromString(src.responseText, "text/html");
    return attrs(doc.body, collect(doc.body, [])).map(function (u) { return { kind: u.kind, key: keyOf(u) }; })
      .filter(function (u) { return (u.kind === "html" ? EN_HTML : EN_TEXT)[u.key] == null; });
  }

  window.I18N = { collect: collect, attrs: attrs, keyOf: keyOf, norm: norm, missing: missing };

  if (lang !== "en") return;

  function apply(root) {
    var EN_HTML = window.EN_HTML || {}, EN_TEXT = window.EN_TEXT || {};
    var units = attrs(root, collect(root, []));
    units.forEach(function (u) {
      var key = keyOf(u);
      var en = u.kind === "html" ? EN_HTML[key] : EN_TEXT[key];
      if (en == null) return;
      switch (u.kind) {
        case "html": u.node.innerHTML = en; break;
        case "text": u.node.text = en; break;
        case "node": {
          // 前後の空白は残す（ほかの要素とくっつかないように）
          var v = u.node.nodeValue;
          u.node.nodeValue = (/^\s/.test(v) ? " " : "") + en + (/\s$/.test(v) ? " " : "");
          break;
        }
        case "attr": u.node.setAttribute(u.attr, en); break;
      }
    });
    var t = norm(document.title);
    if (EN_TEXT[t]) document.title = EN_TEXT[t];
  }

  window.I18N.apply = apply;
  // このスクリプトは body の最後（settings.js の前）で読むので、ここで置き換えれば settings.js は英語の画面を前提に動ける
  if (document.body) apply(document.body);
})();
