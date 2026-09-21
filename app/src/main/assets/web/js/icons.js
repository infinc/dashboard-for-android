/*
 * WMO 天気コード → SVG アイコンと日本語ラベル。
 * 外部アイコンフォントに依存しない（オフラインでも欠けないようにするため）。
 * Chrome 81 で確実に描けるプリミティブ（circle / path / line）だけで構成している。
 */
(function (global) {
  "use strict";

  var SUN = "#FFB347";
  var MOON = "#C7D2E0";
  var CLOUD = "#8FA0B3";
  var RAIN = "#4DD4FF";
  var SNOW = "#DDEBF7";
  var BOLT = "#FFD166";

  function svg(inner) {
    return '<svg class="wx-icon" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">' + inner + "</svg>";
  }

  function sun(cx, cy, r) {
    var rays = "";
    for (var i = 0; i < 8; i++) {
      var a = (Math.PI / 4) * i;
      var x1 = cx + Math.cos(a) * (r + 4), y1 = cy + Math.sin(a) * (r + 4);
      var x2 = cx + Math.cos(a) * (r + 9), y2 = cy + Math.sin(a) * (r + 9);
      rays += '<line x1="' + x1.toFixed(1) + '" y1="' + y1.toFixed(1) + '" x2="' + x2.toFixed(1) +
        '" y2="' + y2.toFixed(1) + '" stroke="' + SUN + '" stroke-width="2.6" stroke-linecap="round"/>';
    }
    return rays + '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="' + SUN + '"/>';
  }

  function moon(cx, cy, r) {
    return '<path d="M' + (cx + r * 0.45) + ' ' + (cy - r) +
      ' a' + r + ' ' + r + ' 0 1 0 ' + (r * 0.9) + ' ' + (r * 1.45) +
      ' a' + (r * 0.85) + ' ' + (r * 0.85) + ' 0 1 1 -' + (r * 0.9) + ' -' + (r * 1.45) + 'z" fill="' + MOON + '"/>';
  }

  function cloud(x, y, s, color) {
    var c = color || CLOUD;
    return '<path transform="translate(' + x + ',' + y + ') scale(' + s + ')" fill="' + c + '" d="' +
      "M11 24h26a9 9 0 0 0 .6-18 13 13 0 0 0-24.3-3.2A9.5 9.5 0 0 0 11 24z" + '"/>';
  }

  function drops(y, n, color) {
    var out = "";
    for (var i = 0; i < n; i++) {
      var x = 20 + i * 10;
      out += '<line x1="' + x + '" y1="' + y + '" x2="' + (x - 3) + '" y2="' + (y + 9) +
        '" stroke="' + (color || RAIN) + '" stroke-width="3" stroke-linecap="round"/>';
    }
    return out;
  }

  function flakes(y, n) {
    var out = "";
    for (var i = 0; i < n; i++) {
      out += '<circle cx="' + (20 + i * 10) + '" cy="' + (y + 5) + '" r="2.6" fill="' + SNOW + '"/>';
    }
    return out;
  }

  var BOLT_PATH = '<path d="M34 40l-9 12h7l-3 10 11-14h-7l4-8z" fill="' + BOLT + '"/>';

  function build(code, isDay) {
    var c = Number(code);
    if (isNaN(c)) c = -1;

    if (c === 0 || c === 1) return svg(isDay ? sun(32, 30, 12) : moon(32, 30, 13));
    if (c === 2) return svg((isDay ? sun(24, 22, 9) : moon(24, 22, 10)) + cloud(16, 24, 1.0));
    if (c === 3) return svg(cloud(14, 20, 1.15));
    if (c === 45 || c === 48) {
      return svg(cloud(14, 14, 1.1) +
        '<line x1="14" y1="46" x2="50" y2="46" stroke="' + CLOUD + '" stroke-width="3" stroke-linecap="round"/>' +
        '<line x1="19" y1="54" x2="45" y2="54" stroke="' + CLOUD + '" stroke-width="3" stroke-linecap="round"/>');
    }
    if (c >= 51 && c <= 57) return svg(cloud(14, 12, 1.05) + drops(42, 3));
    if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return svg(cloud(14, 10, 1.1) + drops(42, 4));
    if ((c >= 71 && c <= 77) || c === 85 || c === 86) return svg(cloud(14, 10, 1.1) + flakes(42, 4));
    if (c >= 95) return svg(cloud(14, 8, 1.1) + BOLT_PATH);
    return svg(cloud(14, 20, 1.15));
  }

  var LABELS = {
    0: "快晴", 1: "晴れ", 2: "一部曇り", 3: "曇り",
    45: "霧", 48: "霧氷",
    51: "弱い霧雨", 53: "霧雨", 55: "強い霧雨", 56: "着氷性の霧雨", 57: "着氷性の霧雨",
    61: "弱い雨", 63: "雨", 65: "強い雨", 66: "着氷性の雨", 67: "着氷性の雨",
    71: "弱い雪", 73: "雪", 75: "強い雪", 77: "霧雪",
    80: "にわか雨", 81: "にわか雨", 82: "激しいにわか雨",
    85: "にわか雪", 86: "強いにわか雪",
    95: "雷雨", 96: "雷雨（ひょう）", 99: "雷雨（ひょう）"
  };

  global.WxIcons = {
    svg: build,
    label: function (code) {
      var l = LABELS[Number(code)];
      return l || "—";
    }
  };
})(window);
