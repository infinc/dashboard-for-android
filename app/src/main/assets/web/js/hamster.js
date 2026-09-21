/*
 * 画面下の回し車とハムスター。
 *
 * 取りうる動きは 4 つだけ。
 *   走る   ── 車の中を走る
 *   休む   ── 車の中で止まる
 *   歩く   ── 車の外を左右に歩く
 *   立ち止まる ── 外で止まる
 * 寝る・跳ぶ・外を走る、といった動きは持たない。
 * 外を歩き終えたら必ず車へ戻り、また走り出す。
 *
 * 見た目は CSS 側にあり、ここは状態の切り替えと、外にいる間の位置だけを受け持つ。
 * 対象 WebView は Chrome 81 相当のため、ES2020 までの構文に留める。
 *
 * 壁掛けで一日中動かすので、位置が変わる「歩く」の間だけ更新ループを回し、
 * それ以外の状態では止める。
 */
(function () {
  "use strict";

  var RUN_MIN_MS = 18000, RUN_MAX_MS = 40000;
  var REST_MIN_MS = 10000, REST_MAX_MS = 24000;
  var STAND_MIN_MS = 2500, STAND_MAX_MS = 6500;
  var WALK_SPEED = 32;        // 外を歩く速さ(px/秒)
  var STEP_MS = 33;           // 歩いている間の更新間隔（約 30fps）

  /* 車の中にいるときの位置。CSS の translate(-0.8em, 1.85em) に対応する。 */
  var BASE_X = -5.2;
  var BASE_Y = 13;

  var root = null;            // #hamster
  var mover = null;           // .hamster（動かす対象）
  var x = 0;                  // 車の中心からの左右のずれ(px)
  var dir = -1;               // -1 = 左向き（既定の向き）、1 = 右向き
  var ticker = null;
  var timer = null;
  var legsLeft = 0;           // 外で歩き回る残り回数

  function rand(min, max) { return min + Math.random() * (max - min); }

  /*
   * 外を歩ける範囲。画面からはみ出さず、
   * 右下のフッターのボタン（更新・設定・ブラウズ）の上にも立たないようにする。
   */
  function minX() { return -(window.innerWidth / 2 - 70); }
  function maxX() { return window.innerWidth / 2 - 250; }

  function setState(name) {
    root.className = name || "";
  }

  /** 外にいる間の位置。向きに応じて左右を反転する（既定は左向き）。 */
  function place() {
    mover.style.transform =
      "rotate(0deg) translate(" + (BASE_X + x).toFixed(1) + "px," + BASE_Y + "px)" +
      (dir > 0 ? " scaleX(-1)" : "");
  }

  /** 車の中へ戻すときは、位置の指定を消して CSS のアニメーションに返す。 */
  function clearPlace() {
    mover.style.transform = "";
  }

  function stopTicker() {
    if (ticker) { clearInterval(ticker); ticker = null; }
  }

  function after(ms, fn) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(fn, ms);
  }

  // ------------------------------------------------------------ 車の中

  function run() {
    stopTicker();
    clearPlace();
    setState("");
    // 走ったあとは、車の中で休むか、外へ出るかのどちらか。
    after(rand(RUN_MIN_MS, RUN_MAX_MS), function () {
      if (Math.random() < 0.5) rest(); else goOutside();
    });
  }

  function rest() {
    stopTicker();
    clearPlace();
    setState("is-resting");
    after(rand(REST_MIN_MS, REST_MAX_MS), run);
  }

  // ------------------------------------------------------------ 車の外

  function goOutside() {
    legsLeft = 2 + Math.floor(Math.random() * 3);   // 2〜4 か所を回る
    walkTo(spot(), nextOutside);
  }

  /** 次の行き先。いまいる場所から離れたところを選ぶ。 */
  function spot() {
    var lo = minX(), hi = maxX();
    for (var i = 0; i < 6; i++) {
      var candidate = rand(lo, hi);
      if (Math.abs(candidate - x) > 90) return candidate;
    }
    return x > (lo + hi) / 2 ? lo : hi;
  }

  function nextOutside() {
    if (legsLeft-- <= 0) {
      // 車へ戻って、また走り出す
      walkTo(0, run);
      return;
    }
    stand(function () { walkTo(spot(), nextOutside); });
  }

  function stand(done) {
    stopTicker();
    setState("is-standing");
    place();
    after(rand(STAND_MIN_MS, STAND_MAX_MS), done);
  }

  function walkTo(target, done) {
    setState("is-walking");
    dir = target < x ? -1 : 1;
    place();
    stopTicker();
    var last = Date.now();
    ticker = setInterval(function () {
      var now = Date.now();
      var dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      var step = WALK_SPEED * dt;
      if (Math.abs(target - x) <= step) {
        x = target;
        place();
        stopTicker();
        done();
        return;
      }
      x += dir * step;
      place();
    }, STEP_MS);
  }

  // ------------------------------------------------------------ 表示の切り替え

  var visible = true;

  function stopAll() {
    stopTicker();
    if (timer) { clearTimeout(timer); timer = null; }
  }

  /** いまの visible を実際の DOM とタイマーに反映する。 */
  function apply() {
    if (!root) return;
    root.style.display = visible ? "" : "none";
    if (visible) {
      run();
    } else {
      // 見えていない間は状態遷移も歩行も止める。
      // 外を歩いている途中で消すと 33ms の周期が回り続けるため。
      stopAll();
      clearPlace();
      setState("");
    }
  }

  /*
   * ダッシュボード側（設定の反映）から呼ぶ。
   * init() より先に呼ばれても値だけ覚えておき、init() で当てる。
   */
  window.Hamster = {
    setVisible: function (on) {
      on = on !== false;
      if (on === visible) return;
      visible = on;
      apply();
    }
  };

  // ------------------------------------------------------------ 起動

  function init() {
    root = document.getElementById("hamster");
    if (!root) return;
    mover = root.querySelector(".hamster");
    if (!mover) return;

    // 画面の向きが変わったら歩ける範囲も変わる。外にいるなら中へ収める。
    window.addEventListener("resize", function () {
      if (root.className.indexOf("is-walking") < 0 &&
          root.className.indexOf("is-standing") < 0) return;
      x = Math.max(minX(), Math.min(maxX(), x));
      place();
    });

    apply();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
