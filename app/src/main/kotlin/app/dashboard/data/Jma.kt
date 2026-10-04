package app.dashboard.data

import app.dashboard.i18n.Lang
import java.util.Locale

/**
 * 気象庁の日本語の値を、いまの言語で出す（英語のときだけ英語に直す）。
 *
 * 判定（警報か注意報か・レベル 3 以上か・通知の比較）は日本語のまま行うので、DisasterState には日本語を持ち、
 * 画面に出す直前にここを通す。地名など気象庁が英語の名前を出しているもの（震央の en_anm、area.json の enName、台風の名前）は
 * 別の項目に持って [pick] で選ぶ。表に無い値は日本語のまま出す（推測で誤った英語にしない）。
 */
object Jma {

    fun pick(ja: String?, en: String?): String? = if (Lang.en) en ?: ja else ja

    /** 警報・注意報の種別（[DisasterRepository.WARNING_KINDS] の名前）。 */
    fun kind(ja: String): String {
        if (!Lang.en) return ja
        KINDS[ja]?.let { return it }
        if (ja.startsWith("コード")) return "Code " + ja.removePrefix("コード")
        return ja
    }

    /** 台風の号数（"台風25号" → "Typhoon No. 25"）。 */
    fun typhoonNumber(ja: String?): String? {
        if (ja == null || !Lang.en) return ja
        val n = Regex("台風(\\d+)号").find(ja)?.groupValues?.get(1) ?: return ja
        return "Typhoon No. $n"
    }

    fun scale(ja: String?): String? = if (ja == null || !Lang.en) ja else SCALES[ja] ?: ja

    fun intensity(ja: String?): String? = if (ja == null || !Lang.en) ja else INTENSITIES[ja] ?: ja

    /** 16 方位と「全域」「ほとんど停滞」など。 */
    fun direction(ja: String?): String? {
        if (ja == null || !Lang.en) return ja
        return DIRECTIONS[ja] ?: ja
    }

    /** 台風の進路（"北北東" へ）。英語では "NNE"。 */
    fun course(ja: String?): String? = direction(ja)

    fun category(ja: String?): String? = if (ja == null || !Lang.en) ja else CATEGORIES[ja] ?: ja

    /** 中心位置の表現。気象庁は日本語の地域名（"小笠原近海"）しか出さないので、英語では緯度経度にする。 */
    fun location(ja: String?, lat: Double?, lon: Double?): String? {
        if (!Lang.en) return ja
        if (lat == null || lon == null) return ja
        return String.format(Locale.US, "%.1f°%s %.1f°%s", kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")
    }

    /** 津波の情報の題名。 */
    fun tsunami(ja: String?): String? {
        if (ja == null || !Lang.en) return ja
        val parts = buildList {
            if ("大津波警報" in ja) add("Major tsunami warning")
            if (Regex("(?<!大)津波警報").containsMatchIn(ja)) add("Tsunami warning")
            if ("注意報" in ja) add("Tsunami advisory")
            if ("予報" in ja) add("Tsunami forecast")
        }
        return if (parts.isEmpty()) "Tsunami information" else parts.joinToString(", ")
    }

    fun volcanoName(ja: String): String = if (!Lang.en) ja else VOLCANOES[ja] ?: VOLCANOES[ja.substringBefore("（")]?.let { base ->
        val inner = Regex("（(.+)）").find(ja)?.groupValues?.get(1)
        if (inner != null) "$base (${VOLCANOES[inner] ?: inner})" else base
    } ?: ja

    /** 噴火警戒レベル・噴火警報の名前（"レベル３（入山規制）"）。 */
    fun volcanoLevel(ja: String): String {
        if (!Lang.en) return ja
        Regex("レベル([１２３４５1-5])").find(ja)?.let { m ->
            val n = m.groupValues[1].map { if (it in '１'..'５') '1' + (it - '１') else it }.joinToString("")
            val note = Regex("（(.+)）").find(ja)?.groupValues?.get(1)?.let { LEVEL_NOTES[it] ?: it }
            return "Level $n" + (note?.let { " ($it)" } ?: "")
        }
        return VOLCANO_WARNINGS[ja] ?: ja
    }

    private val KINDS = mapOf(
        "レベル２大雨注意報" to "Level 2 heavy rain advisory", "レベル３大雨警報" to "Level 3 heavy rain warning",
        "レベル４大雨危険警報" to "Level 4 heavy rain danger warning", "レベル５大雨特別警報" to "Level 5 heavy rain emergency warning",
        "レベル２土砂災害注意報" to "Level 2 landslide advisory", "レベル３土砂災害警報" to "Level 3 landslide warning",
        "レベル４土砂災害危険警報" to "Level 4 landslide danger warning", "レベル５土砂災害特別警報" to "Level 5 landslide emergency warning",
        "レベル２高潮注意報" to "Level 2 storm surge advisory", "レベル３高潮警報" to "Level 3 storm surge warning",
        "レベル４高潮危険警報" to "Level 4 storm surge danger warning", "レベル５高潮特別警報" to "Level 5 storm surge emergency warning",
        "強風注意報" to "Gale advisory", "暴風警報" to "Storm warning", "暴風特別警報" to "Storm emergency warning",
        "風雪注意報" to "Snowstorm advisory", "暴風雪警報" to "Blizzard warning", "暴風雪特別警報" to "Blizzard emergency warning",
        "大雪注意報" to "Heavy snow advisory", "大雪警報" to "Heavy snow warning", "大雪特別警報" to "Heavy snow emergency warning",
        "波浪注意報" to "High wave advisory", "波浪警報" to "High wave warning", "波浪特別警報" to "High wave emergency warning",
        "雷注意報" to "Thunderstorm advisory", "融雪注意報" to "Snowmelt advisory", "濃霧注意報" to "Dense fog advisory",
        "乾燥注意報" to "Dry air advisory", "なだれ注意報" to "Avalanche advisory", "低温注意報" to "Low temperature advisory",
        "霜注意報" to "Frost advisory", "着氷注意報" to "Ice accretion advisory", "着雪注意報" to "Snow accretion advisory",
    )

    private val SCALES = mapOf("大型" to "Large", "超大型" to "Very large")
    private val INTENSITIES = mapOf("強い" to "Strong", "非常に強い" to "Very strong", "猛烈な" to "Violent")
    private val CATEGORIES = mapOf(
        "台風" to "Typhoon", "熱帯低気圧" to "Tropical depression", "温帯低気圧" to "Extratropical cyclone",
        "発達した熱帯低気圧" to "Developed tropical depression",
    )
    private val DIRECTIONS = mapOf(
        "北" to "N", "北北東" to "NNE", "北東" to "NE", "東北東" to "ENE", "東" to "E", "東南東" to "ESE", "南東" to "SE", "南南東" to "SSE",
        "南" to "S", "南南西" to "SSW", "南西" to "SW", "西南西" to "WSW", "西" to "W", "西北西" to "WNW", "北西" to "NW", "北北西" to "NNW",
        "全域" to "All around", "ほとんど停滞" to "Almost stationary", "停滞" to "Stationary", "ゆっくり" to "Slowly",
    )
    private val LEVEL_NOTES = mapOf(
        "活火山であることに留意" to "Potential for increased activity",
        "火口周辺規制" to "Do not approach the crater",
        "入山規制" to "Do not approach the volcano",
        "高齢者等避難" to "Prepare to evacuate",
        "避難" to "Evacuate",
    )
    private val VOLCANO_WARNINGS = mapOf(
        "火口周辺危険" to "Danger near the crater",
        "入山危険" to "Danger on the mountain",
        "周辺海域警戒" to "Caution in surrounding waters",
        "居住地域厳重警戒" to "Danger in residential areas",
        "活火山であることに留意" to "Potential for increased activity",
        "噴火警報（火口周辺）" to "Eruption warning (crater area)",
        "噴火警報（居住地域）" to "Eruption warning (residential areas)",
        "噴火予報" to "Eruption forecast",
    )

    /** 気象庁が常時観測している火山（の主なもの）。表に無い火山は日本語のまま。 */
    private val VOLCANOES = mapOf(
        "桜島" to "Sakurajima", "阿蘇山" to "Asosan", "霧島山" to "Kirishimayama", "新燃岳" to "Shinmoedake", "御鉢" to "Ohachi",
        "えびの高原（硫黄山）周辺" to "Ebino Plateau (Iozan)", "口永良部島" to "Kuchinoerabujima", "諏訪之瀬島" to "Suwanosejima",
        "薩摩硫黄島" to "Satsuma-Iojima", "雲仙岳" to "Unzendake", "九重山" to "Kujusan", "鶴見岳・伽藍岳" to "Tsurumidake and Garandake",
        "浅間山" to "Asamayama", "草津白根山" to "Kusatsu-Shiranesan", "白根山（湯釜付近）" to "Shiranesan (Yugama)", "本白根山" to "Motoshiranesan",
        "箱根山" to "Hakoneyama", "富士山" to "Fujisan", "伊豆大島" to "Izu-Oshima", "三宅島" to "Miyakejima", "八丈島" to "Hachijojima",
        "青ヶ島" to "Aogashima", "伊豆東部火山群" to "Izu-Tobu Volcanoes", "新島" to "Niijima", "神津島" to "Kozushima",
        "西之島" to "Nishinoshima", "硫黄島" to "Ioto", "福徳岡ノ場" to "Fukutoku-Okanoba", "ベヨネース列岩" to "Bayonnaise Rocks",
        "須美寿島" to "Sumisujima", "伊豆鳥島" to "Izu-Torishima", "海徳海山" to "Kaitoku Seamount", "噴火浅根" to "Funka Asane",
        "御嶽山" to "Ontakesan", "焼岳" to "Yakedake", "乗鞍岳" to "Norikuradake", "白山" to "Hakusan", "弥陀ヶ原" to "Midagahara",
        "新潟焼山" to "Niigata-Yakeyama", "妙高山" to "Myokosan", "那須岳" to "Nasudake", "日光白根山" to "Nikko-Shiranesan",
        "蔵王山" to "Zaozan", "吾妻山" to "Azumayama", "安達太良山" to "Adatarayama", "磐梯山" to "Bandaisan", "栗駒山" to "Kurikomayama",
        "鳥海山" to "Chokaisan", "岩手山" to "Iwatesan", "秋田駒ヶ岳" to "Akita-Komagatake", "秋田焼山" to "Akita-Yakeyama",
        "八甲田山" to "Hakkodasan", "十和田" to "Towada", "岩木山" to "Iwakisan", "八幡平" to "Hachimantai", "肘折" to "Hijiori",
        "十勝岳" to "Tokachidake", "雌阿寒岳" to "Meakandake", "樽前山" to "Tarumaesan", "有珠山" to "Usuzan", "北海道駒ヶ岳" to "Hokkaido-Komagatake",
        "恵山" to "Esan", "倶多楽" to "Kuttara", "大雪山" to "Taisetsuzan", "アトサヌプリ" to "Atosanupuri", "丸山" to "Maruyama",
        "雌阿寒岳（中マチネシリ火口）" to "Meakandake (Naka-Machineshiri crater)", "知床硫黄山" to "Shiretoko-Iozan", "羅臼岳" to "Rausudake",
        "鶴見岳" to "Tsurumidake", "由布岳" to "Yufudake", "福江火山群" to "Fukue Volcanoes", "開聞岳" to "Kaimondake", "口之島" to "Kuchinoshima",
        "中之島" to "Nakanoshima", "硫黄鳥島" to "Io-Torishima", "米丸・住吉池" to "Yonemaru and Sumiyoshiike", "若尊" to "Wakamiko",
    )
}
