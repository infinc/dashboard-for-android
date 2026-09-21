package app.walldash.data

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
 * 警報の「種別名」は出さない。気象庁が種別コードの名称テーブルを公開しておらず
 * （const の JSON は 404）、推測で名前を当てると誤表示になるため。
 * 代わりに気象庁自身が書いた [headline] の本文と、発表中の地域名を出す。
 */
@Serializable
data class DisasterState(
    val available: Boolean = false,
    val officeName: String? = null,
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
    /** "balanced" | "clock" | "weather" — v1 は固定レイアウト3種から選ぶ（並び替えは持たない） */
    val layout: String = "balanced",
    val showClock: Boolean = true,
    val showWifi: Boolean = true,
    val showWeather: Boolean = true,
    val showHourly: Boolean = true,
    val showDaily: Boolean = true,
    val showSun: Boolean = true,
    /** アクセント色（CSS の色文字列） */
    val accent: String = "#4DD4FF",
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
)

/**
 * 天気カードに出せる項目。値は dashboard.js の WX_FIELDS のキーと一致させること。
 * 既定はこの順で 9 項目すべて（3 列 3 行にちょうど収まる）。
 */
val DEFAULT_WEATHER_FIELDS: List<String> = listOf(
    "apparent", "pm25", "pop", "rain", "humidity", "wind", "uv", "aqi", "visibility",
)

@Serializable
data class DisasterConfig(
    val enabled: Boolean = false,
    /** 気象庁の府県予報区コード（area.json の offices）。既定は東京都。 */
    val officeCode: String = "130000",
    val officeName: String = "東京都",
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
    val spotify: SpotifyConfig = SpotifyConfig(),
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
)

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
    val config: PublicConfig,
    /** この応答が機微情報をマスクしたものかどうか（LAN 未認証時 true）。 */
    val masked: Boolean = false,
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
