package app.dashboard.data

import kotlinx.serialization.Serializable

/**
 * SSID が取得できない理由をそのまま UI に出すためのコード。
 * ダッシュボード上で欄を空白にせず、「なぜ出ないか」と「どうすれば出るか」を示すために使う。
 */
object SsidStatus {
    const val OK = "ok"
    const val PERMISSION_REQUIRED = "permission_required"
    const val LOCATION_SERVICES_OFF = "location_services_off"
    const val UNAVAILABLE = "unavailable"
}

/**
 * Wi-Fi の現在状態。
 *
 * リンク速度はいずれも Wi-Fi の物理リンクレートであり、実効スループットではない。
 * 実効スループット測定（スピードテスト）は本アプリでは行わない。
 */
@Serializable
data class WifiState(
    val connected: Boolean = false,
    val ssid: String? = null,
    val ssidStatus: String = SsidStatus.UNAVAILABLE,
    val linkSpeedMbps: Int? = null,
    /** 下り（受信）方向のリンク速度。API 29 未満では取れないので null。 */
    val rxLinkSpeedMbps: Int? = null,
    /** 上り（送信）方向のリンク速度。API 29 未満では取れないので null。 */
    val txLinkSpeedMbps: Int? = null,
    val rssiDbm: Int? = null,
    /** 0..4 に正規化した強度。バー表示用。 */
    val signalLevel: Int = 0,
    val band: String? = null,
    val frequencyMhz: Int? = null,
    val ipAddress: String? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class WeatherCurrent(
    val temperature: Double? = null,
    val apparentTemperature: Double? = null,
    val humidity: Int? = null,
    val windSpeed: Double? = null,
    /** 風向（風が吹いてくる方角）0..359 度。UI 側で N/NE などに変換する。 */
    val windDirection: Int? = null,
    val precipitationProbability: Int? = null,
    /** 直近 1 時間の降水量 (mm)。降水確率とは別の指標なので併記する。 */
    val precipitation: Double? = null,
    /** UV 指数。Open-Meteo の current には無いので時間別から現在時刻の値を拾う。 */
    val uvIndex: Double? = null,
    /** 視程 (m)。UI では km に直して出す。 */
    val visibilityMeters: Double? = null,
    /** European AQI（0 が最良）。Air Quality API から取るため天気とは別系統で失敗し得る。 */
    val aqi: Int? = null,
    /** PM2.5 (μg/m³)。AQI と同じ Air Quality API から取る。 */
    val pm25: Double? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean = true,
)

@Serializable
data class WeatherHour(
    val time: String,
    val temperature: Double? = null,
    val precipitationProbability: Int? = null,
    val weatherCode: Int? = null,
)

@Serializable
data class WeatherDay(
    val date: String,
    val tempMax: Double? = null,
    val tempMin: Double? = null,
    val weatherCode: Int? = null,
    /** 1 日の降水量合計 (mm)。 */
    val precipitationSum: Double? = null,
    /** その日の降水確率の最大値 (%)。 */
    val precipitationProbabilityMax: Int? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
)

@Serializable
data class WeatherState(
    val available: Boolean = false,
    val placeName: String? = null,
    val timezone: String? = null,
    val current: WeatherCurrent = WeatherCurrent(),
    val hourly: List<WeatherHour> = emptyList(),
    val daily: List<WeatherDay> = emptyList(),
    /** 最終取得成功時刻(epoch ms)。0 なら一度も取得できていない。 */
    val fetchedAt: Long = 0L,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- 端末状態

@Serializable
data class DeviceStats(
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val batteryTemperatureC: Double? = null,
    /** CPU 使用率 0..100。cpuidle が読めない端末では null。 */
    val cpuPercent: Int? = null,
    val storageFreeBytes: Long = 0,
    val storageTotalBytes: Long = 0,
    val memoryAvailableBytes: Long = 0,
    val memoryTotalBytes: Long = 0,
    /**
     * いま流れている通信量（bit/秒）。リンク速度と違い実際に出ている速度。
     * 起動直後は前回値が無く差分を取れないので null。
     */
    val rxBitsPerSec: Long? = null,
    val txBitsPerSec: Long? = null,
)

// ---------------------------------------------------------------- 防災（気象庁）

@Serializable
data class WarningArea(
    val code: String,
    val name: String,
    val count: Int,
    /** 発表中の種別名。例: ["濃霧注意報", "雷注意報"]。 */
    val kinds: List<String> = emptyList(),
    /** 1 つでも「警報」以上が含まれるか。UI の色分けに使う。 */
    val severe: Boolean = false,
)

@Serializable
data class QuakeInfo(
    val occurredAt: String? = null,
    val epicenter: String? = null,
    val magnitude: String? = null,
    val maxIntensity: String? = null,
    val title: String? = null,
)

/**
 * 気象庁の警報・注意報と地震情報。
 *
 * 警報・注意報は天気の地点がある市町村のものだけを持つ（[activeAreas] は 0 か 1 件）。
 * 種別名は気象庁の警報ページと同じ表記（例: レベル４土砂災害危険警報）。
 */
@Serializable
data class DisasterState(
    val available: Boolean = false,
    /** 天気の地点が属する府県予報区。例: 東京都 */
    val officeName: String? = null,
    /**
     * 天気の地点がある市町村（気象庁の市町村区分の名前）。例: 新宿区
     * [available] なのに null なら、地点から市町村を決められなかった（国外の地点など）。
     */
    val areaName: String? = null,
    val headline: String? = null,
    val reportedAt: String? = null,
    val activeAreas: List<WarningArea> = emptyList(),
    /** 設定した震度以上で直近の 1 件だけ。過去の一覧は持たない。 */
    val quakes: List<QuakeInfo> = emptyList(),
    /** 発表中の津波警報・注意報。無ければ空。 */
    val tsunami: List<TsunamiInfo> = emptyList(),
    /** 発生中の台風。複数同時に存在し得る。 */
    val typhoons: List<TyphoonInfo> = emptyList(),
    /** 噴火警報・予報が出ている火山（全国）。 */
    val volcanoes: List<VolcanoInfo> = emptyList(),
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

/**
 * 津波情報。
 *
 * 気象庁の一覧 JSON は平常時から空配列なので、中身の形を実データで確認できない。
 * 取れたものだけ入れ、題名が取れなくても「津波情報」として出せるようにしておく。
 */
@Serializable
data class TsunamiInfo(
    val title: String? = null,
    val reportedAt: String? = null,
)

/** 台風。数値はすべて気象庁の文字列表記のまま持つ（単位換算はしない）。 */
@Serializable
data class TyphoonInfo(
    /** 気象庁の熱帯低気圧の識別子（"TC2632" など）。全画面の進路図を取りに行くのに使う。 */
    val id: String? = null,
    /** 「台風25号」。番号が取れなければ null。 */
    val number: String? = null,
    /** アジア名。例: ドゥージェン */
    val name: String? = null,
    /** 大きさ。例: 大型。小さい台風では付かない。 */
    val scale: String? = null,
    /** 強さ。例: 強い。発達していない台風では付かない。 */
    val intensity: String? = null,
    /** 中心位置の表現。例: 日本の南 */
    val location: String? = null,
    val pressureHpa: String? = null,
    val maxWindMps: String? = null,
    val gustMps: String? = null,
    val course: String? = null,
    val speedKmh: String? = null,
    val reportedAt: String? = null,
)

/**
 * 台風の進路図（全画面）に描くもの。気象庁の forecast.json（位置・円）と specifications.json（文字の情報）をまとめたもの。
 * 緯度・経度は度、半径は km。
 */
@Serializable
data class TyphoonTrack(
    val id: String,
    val number: String? = null,
    val name: String? = null,
    val reportedAt: String? = null,
    /** 台風になってからの経路（古い順）。 */
    val track: List<LatLon> = emptyList(),
    /** 台風になる前（熱帯低気圧）の経路。 */
    val preTrack: List<LatLon> = emptyList(),
    /** 実況（[TyphoonPoint.hours] = 0）と、12・24・48…時間後の予報。 */
    val points: List<TyphoonPoint> = emptyList(),
    /** 実況の強風域（風速 15 m/s 以上）。中心は台風の中心とずれることがある。 */
    val gale: Circle? = null,
    /** 実況の暴風域（風速 25 m/s 以上）。 */
    val storm: Circle? = null,
)

@Serializable
data class LatLon(val lat: Double, val lon: Double)

@Serializable
data class Circle(val center: LatLon, val radiusKm: Double)

@Serializable
data class TyphoonPoint(
    /** 0 = 実況、12 = 12 時間後の予報…。 */
    val hours: Int,
    /** その時刻（"2026-09-29T03:00:00+09:00"）。 */
    val validTime: String? = null,
    val center: LatLon,
    /** 予報円の半径（予報だけ）。 */
    val circleKm: Double? = null,
    /** 予報は暴風警戒域の半径、実況は暴風域の半径。暴風域が無ければ null。 */
    val stormKm: Double? = null,
    val category: String? = null,
    val scale: String? = null,
    val intensity: String? = null,
    val location: String? = null,
    val pressureHpa: String? = null,
    val maxWindMps: String? = null,
    val gustMps: String? = null,
    val course: String? = null,
    val speedKmh: String? = null,
    /** 実況の強風域の書き方（"南東 220km ・ 北西 165km" / "全域 300km"）。 */
    val galeText: String? = null,
)

/** 噴火警報・予報が出ている火山 1 つ。 */
@Serializable
data class VolcanoInfo(
    val name: String,
    /** 例: レベル３（入山規制） / 火口周辺危険 */
    val level: String,
    val reportedAt: String? = null,
    /** レベル 3 以上、または立入危険。UI で赤く出す。 */
    val severe: Boolean = false,
)

// ---------------------------------------------------------------- フィード

@Serializable
data class FeedItem(val title: String, val source: String? = null, val publishedAt: String? = null)

@Serializable
data class FeedState(
    val items: List<FeedItem> = emptyList(),
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- メモ（LINE）

/** LINE から届いたメモ 1 件。 */
@Serializable
data class MemoItem(
    /** 中継側で振る識別子。ダッシュボードが再描画の要否を判断するのに使う。 */
    val id: String = "",
    val text: String = "",
    val senderName: String? = null,
    val receivedAt: Long = 0,
)

/**
 * LINE メモの現在状態。
 *
 * [items] が新しい順の本体で、[text] 以下は中継が 1 件しか返さない旧応答との互換用。
 */
@Serializable
data class MemoState(
    val items: List<MemoItem> = emptyList(),
    val text: String? = null,
    val senderName: String? = null,
    val receivedAt: Long = 0,
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- Spotify

/**
 * Spotify で再生中の曲。
 *
 * 取得できるのは「いま鳴っているもの」だけで、履歴や再生操作は扱わない。
 * 壁に出すのはジャケットと曲名なので、必要な項目だけ持つ。
 */
@Serializable
data class SpotifyState(
    /** 連携済みで、Spotify から応答を得られている。 */
    val available: Boolean = false,
    val playing: Boolean = false,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    /** ジャケット画像の URL（Spotify の CDN、HTTPS）。 */
    val albumImageUrl: String? = null,
    val progressMs: Long? = null,
    val durationMs: Long? = null,
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- 設定

@Serializable
data class LanConfig(
    val enabled: Boolean = false,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val pinIterations: Int = 0,
    /**
     * PBKDF2 の擬似乱数関数。API 26 未満は PBKDF2WithHmacSHA256 が無いため SHA1 になる。
     * 端末を跨いで設定を持ち運ばないが、OS 更新後も既存 PIN を検証できるよう保存する。
     */
    val pinAlgorithm: String = "PBKDF2WithHmacSHA256",
)

@Serializable
data class LocationConfig(
    val configured: Boolean = false,
    val name: String = "東京",
    val latitude: Double = 35.6895,
    val longitude: Double = 139.6917,
    val timezone: String = "Asia/Tokyo",
)

@Serializable
data class UnitsConfig(
    /** "c" | "f" */
    val temperature: String = "c",
    /** "kmh" | "ms" */
    val wind: String = "kmh",
    val clock24h: Boolean = true,
    val showSeconds: Boolean = true,
)

@Serializable
data class DisplayConfig(
    val showClock: Boolean = true,
    val showWifi: Boolean = true,
    val showWeather: Boolean = true,
    val showHourly: Boolean = true,
    val showDaily: Boolean = true,
    val showSun: Boolean = true,
    /** アクセント色（CSS の色文字列） */
    val accent: String = "#4DD4FF",
    /** 全体の色の基調。"dark" | "light" */
    val theme: String = "dark",
    /**
     * 背景画像を設定しているときのカードの不透明度 0.2..1.0。小さいほど背景が透けて見える。
     * 背景画像が無いときは使わない（カードは常に不透明）。
     */
    val cardOpacity: Double = 0.6,
    /** カードの背景色（[CardColors] のどれか。空文字は既定）。カード全体で 1 色。不透明度は [cardOpacity] のまま。 */
    val cardColor: String = "",
    /**
     * 操作があるときの画面の明るさ 0.05..1.0。
     * CSS で暗く見せるのではなく、ウィンドウのバックライト輝度として適用する。
     */
    val normalBrightness: Double = 1.0,
    val burnInShiftEnabled: Boolean = true,
    /** 一定時間タッチが無いときに画面を暗くする（バックライト自体を落とす）。 */
    val idleDimEnabled: Boolean = true,
    val idleDimAfterSeconds: Int = 300,
    /** 無操作時のバックライト輝度 0.05..1.0 */
    val idleDimBrightness: Double = 0.15,
    val showDisaster: Boolean = true,
    val showFeed: Boolean = true,
    val showDeviceStats: Boolean = true,
    val showMemo: Boolean = true,
    val showTimer: Boolean = true,
    val showWord: Boolean = true,
    val showSpotify: Boolean = true,
    /** 回し車のハムスター。カードではないが、出す・出さないは同じように選べる。 */
    val showHamster: Boolean = true,

    // ------------------------------------------------ カードごとの見せ方

    /** 時計カードの揃え。"left" | "center" | "right" */
    val clockAlign: String = "left",
    /** 日付の書き方。"ja" = 2026年9月21日 (月) / "slash" = 2026/09/21 (月) */
    val clockDateFormat: String = "ja",

    /**
     * 天気カードに並べる項目と、その順序。
     * 3 列で折り返すので、並べた順にそのまま左上から詰まる。
     */
    val weatherFields: List<String> = DEFAULT_WEATHER_FIELDS,

    val disasterShowTyphoon: Boolean = true,
    val disasterShowVolcano: Boolean = true,
    /** 防災カード右側の強震モニタ。消すと本文が横いっぱいに広がる。 */
    val disasterShowKmoni: Boolean = true,

    /** 時間別予報に何を描くか。"both" | "temp" | "precip" */
    val hourlyMode: String = "both",

    val spotifyShowControls: Boolean = true,
    val spotifyShowProgress: Boolean = true,

    /** Wi-Fi カードの回る地球。 */
    val wifiShowGlobe: Boolean = true,

    // ------------------------------------------------ 後から足したカード
    // 既定はすべて非表示。既定の 12 枚で横向きの画面がちょうど 4 行埋まっているため、
    // 既定で出すと更新しただけで画面に収まらなくなる。

    val showTrain: Boolean = false,
    val showToday: Boolean = false,
    val showRadar: Boolean = false,
    val showCalendar: Boolean = false,
    val showStocks: Boolean = false,
    val showSunMoon: Boolean = false,
    val showCountdown: Boolean = false,
    val showAnalogClock: Boolean = false,
    val showCalculator: Boolean = false,
    val showPhotos: Boolean = false,
    val showCrypto: Boolean = false,

    /** 今日は何の日カードに、過去の今日のできごとを 1 件添える。 */
    val todayShowEvent: Boolean = true,
    /** アナログ時計の秒針をなめらかに動かす（切ると 1 秒ごとに刻む。描き直しが減るので古い端末に優しい）。 */
    val analogSweep: Boolean = true,
    /** アナログ時計の文字盤に数字を入れる。 */
    val analogNumerals: Boolean = true,

    /**
     * 利用者が設定画面の「カードの配置」で決めた並べ方（横向きの画面の行ごと、左から順）。
     * 空なら自動で並べる（[CardLayout.rows] の従来の計算）。幅を 1 度でも動かして保存すると、ここに入る。
     * 行の幅の合計が 24 列に満たないときは、右端がそのまま空く。縦に伸ばしたカードの下の列は、下の行では飛ばして並べる。
     */
    val cardLayout: List<List<CardSlot>> = emptyList(),
)

/**
 * カードの配置の 1 枠。[card] は [CardLayout.Card] の名前、[span] は 24 列のうち何列使うか、
 * [height] は何行ぶんの高さか（2 以上なら下の行の同じ列まで伸びる）。
 */
@Serializable
data class CardSlot(val card: String, val span: Int, val height: Int = 1)

/**
 * 天気カードに出せる項目。値は ui/dashboard/SimpleCards.kt の wxCell() のキーと一致させること。
 * 既定はこの順で 9 項目すべて（3 列 3 行にちょうど収まる）。
 */
val DEFAULT_WEATHER_FIELDS: List<String> = listOf(
    "apparent", "pm25", "pop", "rain", "humidity", "wind", "uv", "aqi", "visibility",
)

/**
 * 通知音。音はアプリ内で合成する（音源ファイルは持たない）。
 * 音色の id は [Tones] の一覧のどれか。[volume] は鳴らす間だけ当てる端末のメディア音量。
 */
@Serializable
data class NotificationConfig(
    val disasterSound: Boolean = true,
    val chargingSound: Boolean = true,
    /** 0.0..1.0。0 にすると鳴らない。 */
    val volume: Double = 0.7,
    val disasterTone: String = Tones.DEFAULT_DISASTER,
    val chargingTone: String = Tones.DEFAULT_CHARGING,
    val timerTone: String = Tones.DEFAULT_TIMER,

    // ------------------------------------------------ 後から足した通知（音とバナー。切ると両方とも出ない）

    /** 電池の残量が [batteryLowPercent] % を切ったとき（充電中は知らせない）。 */
    val batteryLowEnabled: Boolean = true,
    val batteryLowPercent: Int = 20,
    val batteryLowTone: String = Tones.DEFAULT_BATTERY_LOW,
    /** LINE メモに新しいメモが届いたとき。 */
    val memoEnabled: Boolean = true,
    val memoTone: String = Tones.DEFAULT_MEMO,
    /** 電池の温度が [batteryHotC] ℃ を超えたとき。 */
    val batteryHotEnabled: Boolean = true,
    val batteryHotC: Int = 40,
    val batteryHotTone: String = Tones.DEFAULT_BATTERY_HOT,
    /** Wi-Fi の接続が切れたとき。 */
    val wifiLostEnabled: Boolean = true,
    val wifiLostTone: String = Tones.DEFAULT_WIFI_LOST,
    /** [rainMinutes] 分以内に雨が降り始める予報が出たとき（気象庁の降水ナウキャスト。国外の地点は時間別予報）。 */
    val rainEnabled: Boolean = true,
    val rainMinutes: Int = 30,
    val rainTone: String = Tones.DEFAULT_RAIN,
)

/** 通知のしきい値の選択肢（アプリと Web の設定画面で同じもの）。 */
val BATTERY_LOW_CHOICES: List<Int> = listOf(5, 10, 15, 20, 25, 30, 40, 50)
val BATTERY_HOT_CHOICES: List<Int> = listOf(35, 38, 40, 42, 45, 50)
val RAIN_MINUTE_CHOICES: List<Int> = listOf(10, 15, 20, 30, 45, 60)

/**
 * 防災の設定。
 *
 * 警報・注意報の地域は持たない。天気の地点（[LocationConfig]）の市町村を
 * [DisasterRepository] が自動で決める。以前あった府県予報区の選択（officeCode / officeName）は
 * 古い config.json に残っていても読み飛ばされる。
 */
@Serializable
data class DisasterConfig(
    val enabled: Boolean = false,
    /** この震度未満の地震は表示しない。"1".."7"、"5-"/"5+" 等の表記も来る。 */
    val minIntensity: String = "3",
)

@Serializable
data class FeedConfig(
    val enabled: Boolean = false,
    val urls: List<String> = emptyList(),
    val maxItems: Int = 6,
)

/**
 * LINE から届いたメモの取得設定。
 * [token] は秘密なので [PublicConfig] には出さない（設定済みかどうかだけ返す）。
 */
@Serializable
data class MemoConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val token: String? = null,
    val pollIntervalMs: Long = 30_000,
)

/**
 * Spotify 連携。
 *
 * 認可は PKCE 付きの認可コードフローで行う。クライアントシークレットを端末に置かずに済み、
 * 壁掛け端末の中に長期間の秘密を増やさないため。
 * [clientId] は秘密ではない（公開クライアントの識別子）が、[refreshToken] は秘密。
 */
@Serializable
data class SpotifyConfig(
    val enabled: Boolean = false,
    val clientId: String = "",
    val refreshToken: String? = null,
)

/**
 * ブラウズ画面のお気に入り 1 件。
 *
 * favicon は持たない。取得のために別の通信を増やしたくないのと、
 * 壁の前から見るには 16px の絵より文字のほうが早く読めるため。
 */
@Serializable
data class Favorite(
    val url: String,
    /** 一覧に出す名前。ページの title が取れなければホスト名を入れる。 */
    val title: String,
)

/**
 * ブラウズ機能の設定。
 *
 * 壁掛け端末で長い URL を打つのは現実的でないので、一度開いた先を
 * その場で登録して次からは 1 タップで戻れるようにする。
 */
@Serializable
data class BrowserConfig(
    val favorites: List<Favorite> = emptyList(),
)

// ---------------------------------------------------------------- 電車の運行情報（ODPT）

/**
 * 公共交通オープンデータセンター（ODPT）の運行情報。
 * [token] は api.odpt.org 用、[challengeToken] は JR 東日本などが載っている api-challenge.odpt.org 用（どちらも秘密）。
 * [railways] は表示する路線（odpt.Railway:TokyoMetro.Ginza など）。空なら「平常でない路線だけ」を出す。
 */
@Serializable
data class TrainConfig(
    val enabled: Boolean = false,
    val token: String? = null,
    val challengeToken: String? = null,
    val railways: List<String> = emptyList(),
)

@Serializable
data class TrainPublic(
    val enabled: Boolean = false,
    val tokenSet: Boolean = false,
    val challengeTokenSet: Boolean = false,
    val railways: List<String> = emptyList(),
)

/** トークンは書き込み専用。null は変更しない、空文字は消す。 */
@Serializable
data class TrainPatch(
    val enabled: Boolean? = null,
    val token: String? = null,
    val challengeToken: String? = null,
    val railways: List<String>? = null,
)

@Serializable
data class TrainLine(
    val railway: String,
    val title: String,
    val operator: String? = null,
    /** 路線の色（"#F39700" など）。事業者が出していなければ null。 */
    val color: String? = null,
    /** 例: 平常運転 / 遅延 / 運転見合わせ */
    val status: String,
    val text: String? = null,
    /** 平常運転ではない。 */
    val trouble: Boolean = false,
)

@Serializable
data class TrainState(
    val lines: List<TrainLine> = emptyList(),
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

/** 設定画面で路線を選ぶための一覧。 */
@Serializable
data class RailwayChoice(val id: String, val title: String, val operator: String)

// ---------------------------------------------------------------- 今日は何の日（Wikipedia）

@Serializable
data class TodayItem(
    val name: String,
    /** 国・地域（{{JPN}} → 日本 など）。分からなければ null。 */
    val region: String? = null,
    val note: String? = null,
)

@Serializable
data class TodayState(
    /** "2026-09-26"。日付が変わったら取り直す。 */
    val date: String = "",
    val days: List<TodayItem> = emptyList(),
    /** 過去の今日のできごと（"1978年 - …" の形）。 */
    val events: List<String> = emptyList(),
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- カレンダー（iCloud）

/**
 * 予定表。[mode] が "caldav" なら iCloud の CalDAV に Apple ID と App 用パスワードで、
 * "ics" なら共有カレンダーの公開 URL（webcal://〜）から読む。
 * [password] と [icsUrl] は秘密（公開 URL は知っている人なら誰でも予定を読めるため）。
 */
@Serializable
data class CalendarConfig(
    val enabled: Boolean = false,
    val mode: String = "caldav",
    val appleId: String = "",
    val password: String? = null,
    val icsUrl: String? = null,
    /** 今日から何日先までの予定を出すか。 */
    val daysAhead: Int = 7,
)

@Serializable
data class CalendarPublic(
    val enabled: Boolean = false,
    val mode: String = "caldav",
    val appleId: String = "",
    val passwordSet: Boolean = false,
    val icsUrlSet: Boolean = false,
    val daysAhead: Int = 7,
)

@Serializable
data class CalendarPatch(
    val enabled: Boolean? = null,
    val mode: String? = null,
    val appleId: String? = null,
    val password: String? = null,
    val icsUrl: String? = null,
    val daysAhead: Int? = null,
)

@Serializable
data class CalendarEvent(
    val title: String,
    /** 開始(epoch ms)。終日の予定はその日の 0 時（端末の時間帯）。 */
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    val calendar: String? = null,
    /** カレンダーの色（"#FF2968" など）。 */
    val color: String? = null,
)

@Serializable
data class CalendarState(
    val events: List<CalendarEvent> = emptyList(),
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- 写真（iCloud の共有アルバム）

/**
 * 写真カード。iCloud の「共有アルバム」を、公開 Web サイトの URL（https://photos.icloud.com/shared/album/… か https://www.icloud.com/sharedalbum/#B0…）から読む。
 * [albumUrl] は秘密（URL を知っている人は誰でも写真を見られるため）。
 * [intervalSec] は写真を切り替える間隔、[shuffle] は順番を混ぜるか。
 */
@Serializable
data class PhotoConfig(
    val enabled: Boolean = false,
    val albumUrl: String? = null,
    val intervalSec: Int = 60,
    val shuffle: Boolean = true,
)

@Serializable
data class PhotoPublic(
    val enabled: Boolean = false,
    val albumUrlSet: Boolean = false,
    val intervalSec: Int = 60,
    val shuffle: Boolean = true,
)

/** アルバムの URL は書き込み専用。null は変更しない、空文字は消す。 */
@Serializable
data class PhotoPatch(
    val enabled: Boolean? = null,
    val albumUrl: String? = null,
    val intervalSec: Int? = null,
    val shuffle: Boolean? = null,
)

/**
 * 写真の取得状態。写真そのものの URL（署名付きで、知っていれば誰でも開ける）は外に出さず、
 * [PhotoRepository] の中だけで持つ。
 */
@Serializable
data class PhotoState(
    val albumName: String? = null,
    val count: Int = 0,
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- 株価

@Serializable
data class StockSymbol(val symbol: String, val label: String)

val DEFAULT_STOCKS: List<StockSymbol> = listOf(
    StockSymbol("^N225", "日経平均"),
    StockSymbol("^DJI", "NY ダウ"),
    StockSymbol("^IXIC", "ナスダック"),
    StockSymbol("USDJPY=X", "ドル円"),
)

/** [range] はチャートの期間。"1d" | "5d" | "1mo" | "6mo" | "1y" */
@Serializable
data class StocksConfig(
    val symbols: List<StockSymbol> = DEFAULT_STOCKS,
    val range: String = "1d",
)

@Serializable
data class StockQuote(
    val symbol: String,
    val label: String,
    val price: Double? = null,
    /** 前日（期間の始まりの前）の終値からの変化率 %。 */
    val changePercent: Double? = null,
    val currency: String? = null,
    /** チャート用の終値の並び（古い順）。 */
    val points: List<Double> = emptyList(),
    /** 比べる基準の値（前日終値）。チャートに横線で引く。 */
    val base: Double? = null,
)

@Serializable
data class StocksState(
    val quotes: List<StockQuote> = emptyList(),
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- 暗号通貨

/** 設定画面で選べる主な暗号通貨（CoinGecko の ID と表示名）。Web の設定画面の選択肢（settings.html の #cryptoCoin）も同じ並び。 */
val CRYPTO_COINS: List<Pair<String, String>> = listOf(
    "bitcoin" to "ビットコイン（BTC）",
    "ethereum" to "イーサリアム（ETH）",
    "solana" to "ソラナ（SOL）",
    "ripple" to "エックスアールピー（XRP）",
    "binancecoin" to "ビルドアンドビルド（BNB）",
    "dogecoin" to "ドージコイン（DOGE）",
    "cardano" to "カルダノ（ADA）",
    "tron" to "トロン（TRX）",
    "avalanche-2" to "アバランチ（AVAX）",
    "chainlink" to "チェーンリンク（LINK）",
    "polkadot" to "ポルカドット（DOT）",
    "litecoin" to "ライトコイン（LTC）",
    "sui" to "スイ（SUI）",
)

/** 暗号通貨のチャートの期間（日数）。短い順。カードの「−」「＋」はこの並びを 1 つずつ動く。 */
val CRYPTO_RANGES: List<String> = listOf("1", "7", "30", "365")

/** 暗号通貨の値を取り直す間隔の選択肢（分）。 */
val CRYPTO_INTERVALS: List<Int> = listOf(1, 3, 5, 10, 30, 60)

/**
 * 暗号通貨カード。チャートは 1 つだけで、[coin]（CoinGecko の ID。"bitcoin" など）の値動きを出す。
 * [currency] は "jpy" | "usd"、[range] はチャートの期間（日数）"1" | "7" | "30" | "365"、
 * [chart] はチャートの描き方 "line"（折れ線）| "candle"（ろうそく足）、[intervalMin] は取り直す間隔（分。[CRYPTO_INTERVALS] のどれか）。
 */
@Serializable
data class CryptoConfig(
    val coin: String = "bitcoin",
    val currency: String = "jpy",
    val range: String = "1",
    val chart: String = "line",
    val intervalMin: Int = 10,
)

/** ろうそく足の 1 本（始値・高値・安値・終値）。[time] は足の終わりの時刻（epoch ms。カードでは使わず 0 のこともある）。 */
@Serializable
data class CryptoCandle(val open: Double, val high: Double, val low: Double, val close: Double, val time: Long = 0)

/** 全画面で選べるチャートの期間（日数）と表示名。カードより 1 つ多い（90 日）。 */
val CRYPTO_DETAIL_RANGES: List<Pair<String, String>> = listOf("1" to "24 時間", "7" to "7 日", "30" to "30 日", "90" to "90 日", "365" to "1 年")

/**
 * 暗号通貨の全画面に出す詳しい値とチャート。全画面を開いているときだけ取る（保存はしない）。
 * [times] / [prices] / [volumes] は同じ長さで古い順（間引いたもの）。[candles] はろうそく足を選んだときだけ。
 */
data class CryptoDetail(
    val coin: String,
    val currency: String,
    val days: String,
    val name: String?,
    val symbol: String?,
    val rank: Int?,
    val price: Double?,
    /** 期間の始まりからの変化率 %。 */
    val changePercent: Double?,
    val change24h: Double?,
    val high: Double?,
    val low: Double?,
    val marketCap: Double?,
    val volume24h: Double?,
    /** これまでの最高値と、そこからの下落率 %。 */
    val ath: Double?,
    val athChangePercent: Double?,
    val times: List<Long>,
    val prices: List<Double>,
    val volumes: List<Double>,
    val candles: List<CryptoCandle>,
    val fetchedAt: Long,
)

/** [coin]・[currency]・[range] は取得したときの設定。設定を変えた直後に、前の通貨の値を新しい通貨として出さないために持つ。 */
@Serializable
data class CryptoState(
    val coin: String = "",
    val currency: String = "jpy",
    val range: String = "1",
    /** 名前（"Bitcoin"）と記号（"BTC"）。 */
    val name: String? = null,
    val symbol: String? = null,
    val price: Double? = null,
    /** 期間の始まりからの変化率 %。 */
    val changePercent: Double? = null,
    /** 期間の高値・安値。 */
    val high: Double? = null,
    val low: Double? = null,
    /** チャート用の値の並び（古い順）。ろうそく足で取ったときは終値の並び。 */
    val points: List<Double> = emptyList(),
    /** ろうそく足（古い順）。折れ線で取ったときは空。 */
    val candles: List<CryptoCandle> = emptyList(),
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------- カウントダウン

@Serializable
data class CountdownEvent(
    val name: String,
    /** "2027-03-18"（その日だけ）または "12-24"（毎年）。後ろに " 18:30" のように時刻を付けてもよい。 */
    val date: String,
)

/**
 * カウントダウンカード。[builtins] は組み込みの行事のうち出すもの:
 * "newyear"（新年）/ "christmas" / "holiday"（次の祝日）/ "dayoff"（次の休日＝土日・祝日）/ "fullmoon"（次の満月）/ "newmoon"
 */
@Serializable
data class CountdownConfig(
    val builtins: List<String> = listOf("newyear", "christmas", "holiday", "fullmoon"),
    val custom: List<CountdownEvent> = emptyList(),
)

/** 国民の祝日（内閣府の CSV）。 */
@Serializable
data class Holiday(val date: String, val name: String)

/**
 * ダッシュボードの背景画像。画像そのものは filesDir/wallpaper.jpg に置き、ここには設定した時刻だけを持つ。
 * 「全て保存」の差分（[ConfigPatch]）には含めない。選んだ・外したその場で保存する操作のため。
 */
@Serializable
data class WallpaperConfig(
    /** 画像を設定した時刻(epoch ms)。0 なら背景画像なし。画面はこの値の変化で画像を読み直す。 */
    val imageSetAt: Long = 0,
)

@Serializable
data class RefreshConfig(
    val wifiIntervalMs: Long = 2000,
    val weatherIntervalMs: Long = 600_000,
)

@Serializable
data class Config(
    val configVersion: Long = 1,
    val lan: LanConfig = LanConfig(),
    val location: LocationConfig = LocationConfig(),
    val units: UnitsConfig = UnitsConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val refresh: RefreshConfig = RefreshConfig(),
    val disaster: DisasterConfig = DisasterConfig(),
    val feed: FeedConfig = FeedConfig(),
    val memo: MemoConfig = MemoConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val spotify: SpotifyConfig = SpotifyConfig(),
    /**
     * ブラウズのお気に入り。
     *
     * PublicConfig には載せない。どこを見ているかは生活の様子が出るうえ、
     * 端末の前で登録して端末の前で使うものなので、外に出す経路を作る理由がない。
     */
    val browser: BrowserConfig = BrowserConfig(),
    val wallpaper: WallpaperConfig = WallpaperConfig(),
    val train: TrainConfig = TrainConfig(),
    val calendar: CalendarConfig = CalendarConfig(),
    val stocks: StocksConfig = StocksConfig(),
    val countdown: CountdownConfig = CountdownConfig(),
    val photos: PhotoConfig = PhotoConfig(),
    val crypto: CryptoConfig = CryptoConfig(),
)

/** 設定画面へ返す公開用の設定。PIN のハッシュとソルトは絶対に含めない。 */
@Serializable
data class LanPublic(val enabled: Boolean = false, val pinSet: Boolean = false)

/** 端末トークンは返さない。設定済みかどうかだけ伝える。 */
@Serializable
data class MemoPublic(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val tokenSet: Boolean = false,
    val pollIntervalMs: Long = 30_000,
)

/** 更新用トークンは返さない。連携済みかどうかだけ伝える。 */
@Serializable
data class SpotifyPublic(
    val enabled: Boolean = false,
    val clientId: String = "",
    val connected: Boolean = false,
)

@Serializable
data class PublicConfig(
    val configVersion: Long,
    val lan: LanPublic,
    val location: LocationConfig,
    val units: UnitsConfig,
    val display: DisplayConfig,
    val refresh: RefreshConfig,
    val disaster: DisasterConfig,
    val feed: FeedConfig,
    val memo: MemoPublic,
    val spotify: SpotifyPublic = SpotifyPublic(),
    val notifications: NotificationConfig = NotificationConfig(),
    val wallpaper: WallpaperConfig = WallpaperConfig(),
    val train: TrainPublic = TrainPublic(),
    val calendar: CalendarPublic = CalendarPublic(),
    val stocks: StocksConfig = StocksConfig(),
    val countdown: CountdownConfig = CountdownConfig(),
    val photos: PhotoPublic = PhotoPublic(),
    val crypto: CryptoConfig = CryptoConfig(),
    /** Web の設定画面が選択肢を組み立てるための一覧。アプリの設定画面と同じものを使う。 */
    val choices: SettingChoices = SettingChoices.ALL,
)

@Serializable
data class Choice(val value: String, val label: String)

/** 「カードの配置」の 1 枚ぶんの情報（[CardLayout.Card] と同じ）。 */
@Serializable
data class CardChoice(val id: String, val label: String, val span: Int, val min: Int, val flag: String = "")

@Serializable
data class SettingChoices(
    val accents: List<Choice>,
    val tones: List<Choice>,
    val cardColors: List<Choice> = emptyList(),
    val cards: List<CardChoice> = emptyList(),
    val layoutRows: Int = CardLayout.LAYOUT_ROWS,
    val columns: Int = CardLayout.COLUMNS,
) {
    companion object {
        val ALL = SettingChoices(
            accents = Accents.ALL.map { Choice(it.hex, it.label) },
            tones = Tones.ALL.map { Choice(it.id, it.label) },
            cardColors = CardColors.ALL.map { Choice(it.hex, it.label) },
            cards = CardLayout.Card.entries.map { CardChoice(it.name, it.label, it.span, it.min, it.flag) },
        )
    }
}

fun Config.toPublic() = PublicConfig(
    configVersion = configVersion,
    lan = LanPublic(enabled = lan.enabled, pinSet = lan.pinHash != null),
    location = location,
    units = units,
    display = display,
    refresh = refresh,
    disaster = disaster,
    feed = feed,
    memo = MemoPublic(
        enabled = memo.enabled,
        endpoint = memo.endpoint,
        tokenSet = !memo.token.isNullOrBlank(),
        pollIntervalMs = memo.pollIntervalMs,
    ),
    spotify = SpotifyPublic(
        enabled = spotify.enabled,
        clientId = spotify.clientId,
        connected = !spotify.refreshToken.isNullOrBlank(),
    ),
    notifications = notifications,
    wallpaper = wallpaper,
    train = TrainPublic(
        enabled = train.enabled,
        tokenSet = !train.token.isNullOrBlank(),
        challengeTokenSet = !train.challengeToken.isNullOrBlank(),
        railways = train.railways,
    ),
    calendar = CalendarPublic(
        enabled = calendar.enabled,
        mode = calendar.mode,
        appleId = calendar.appleId,
        passwordSet = !calendar.password.isNullOrBlank(),
        icsUrlSet = !calendar.icsUrl.isNullOrBlank(),
        daysAhead = calendar.daysAhead,
    ),
    stocks = stocks,
    countdown = countdown,
    photos = PhotoPublic(
        enabled = photos.enabled,
        albumUrlSet = !photos.albumUrl.isNullOrBlank(),
        intervalSec = photos.intervalSec,
        shuffle = photos.shuffle,
    ),
    crypto = crypto,
)

/**
 * 設定画面の「全て保存」。公開設定・LINE メモ・Spotify を 1 回で保存する。
 * トークン類は書き込み専用で、この経路からも読み出せない。
 */
@Serializable
data class SaveAllRequest(
    val settings: ConfigPatch = ConfigPatch(),
    val memo: MemoPatch? = null,
    val spotify: SpotifyPatch? = null,
    val train: TrainPatch? = null,
    val calendar: CalendarPatch? = null,
    val photos: PhotoPatch? = null,
)

/** 設定画面から送られてくる更新差分。未指定(null)の項目は変更しない。 */
@Serializable
data class ConfigPatch(
    val location: LocationConfig? = null,
    val units: UnitsConfig? = null,
    val display: DisplayConfig? = null,
    val refresh: RefreshConfig? = null,
    val disaster: DisasterConfig? = null,
    val feed: FeedConfig? = null,
    val notifications: NotificationConfig? = null,
    val stocks: StocksConfig? = null,
    val countdown: CountdownConfig? = null,
    val crypto: CryptoConfig? = null,
)

/** Spotify 設定の更新。refreshToken は認可の経路でしか入らない。 */
@Serializable
data class SpotifyPatch(
    val enabled: Boolean? = null,
    val clientId: String? = null,
)

/** メモ設定の更新。token は書き込み専用で、読み出し経路は用意しない。 */
@Serializable
data class MemoPatch(
    val enabled: Boolean? = null,
    val endpoint: String? = null,
    val token: String? = null,
    val pollIntervalMs: Long? = null,
)

// ---------------------------------------------------------------- API 応答

@Serializable
data class DeviceState(
    /** サーバー時刻(epoch ms)。クライアント時計のズレ補正にのみ使う。 */
    val serverTime: Long,
    val deviceTimezone: String,
    val wifi: WifiState,
    val weather: WeatherState,
    val deviceStats: DeviceStats = DeviceStats(),
    val disaster: DisasterState = DisasterState(),
    val feed: FeedState = FeedState(),
    val memo: MemoState = MemoState(),
    val spotify: SpotifyState = SpotifyState(),
    val train: TrainState = TrainState(),
    val today: TodayState = TodayState(),
    val calendar: CalendarState = CalendarState(),
    val stocks: StocksState = StocksState(),
    val holidays: List<Holiday> = emptyList(),
    val photos: PhotoState = PhotoState(),
    val crypto: CryptoState = CryptoState(),
    val config: PublicConfig,
)

@Serializable
data class GeocodeResult(
    val name: String,
    val admin: String? = null,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
)

@Serializable
data class ApiError(val error: String, val detail: String? = null)
