package app.dashboard.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自作カードの値の場所（JsonPick）・読み取り（WidgetRead）・しきい値・書式・設定の整え。 */
class WidgetsTest {

    private val ha = """
        {"entity_id":"sensor.living_temperature","state":"23.45",
         "attributes":{"unit_of_measurement":"°C","friendly_name":"居間","a.b":{"c":7}},
         "list":[{"name":"台所","value":21.5},{"name":"寝室","value":"19"}],
         "flag":true}
    """.trimIndent()
    private val root = Http.json.parseToJsonElement(ha)

    @Test
    fun `paths are parsed with keys, indexes and quoted keys`() {
        assertEquals(listOf(JsonPick.Step.Key("a"), JsonPick.Step.Key("b")), JsonPick.parse("a.b"))
        assertEquals(listOf(JsonPick.Step.Key("items"), JsonPick.Step.Index(0), JsonPick.Step.Key("name")), JsonPick.parse("$.items[0].name"))
        assertEquals(listOf(JsonPick.Step.Key("a.b"), JsonPick.Step.Key("c")), JsonPick.parse("[\"a.b\"].c"))
        assertEquals(emptyList<JsonPick.Step>(), JsonPick.parse(""))
        assertEquals(listOf(JsonPick.Step.Index(-1)), JsonPick.parse("[-1]"))
        assertNull(JsonPick.parse("a..b"))
        assertNull(JsonPick.parse("a."))
        assertNull(JsonPick.parse("a[x]"))
        assertNull(JsonPick.parse("a]"))
        assertNull(JsonPick.parse("a[0"))
    }

    @Test
    fun `values are picked from the response`() {
        assertEquals("23.45", JsonPick.text(JsonPick.pick(root, "state")))
        assertEquals(23.45, JsonPick.number(JsonPick.pick(root, "state"))!!, 1e-9)
        assertEquals("°C", JsonPick.text(JsonPick.pick(root, "attributes.unit_of_measurement")))
        assertEquals(7.0, JsonPick.number(JsonPick.pick(root, "attributes[\"a.b\"].c"))!!, 1e-9)
        assertEquals("寝室", JsonPick.text(JsonPick.pick(root, "list[-1].name")))
        assertEquals(1.0, JsonPick.number(JsonPick.pick(root, "flag"))!!, 1e-9)
        assertNull(JsonPick.pick(root, "list[5]"))
        assertNull(JsonPick.pick(root, "state.x"))
        assertNull(JsonPick.number(JsonPrimitive("on")))
        assertEquals(1234.5, JsonPick.number(JsonPrimitive("1,234.5"))!!, 1e-9)
    }

    @Test
    fun `join quotes keys that are not identifiers`() {
        assertEquals("a", JsonPick.join("", JsonPick.Step.Key("a")))
        assertEquals("a.b", JsonPick.join("a", JsonPick.Step.Key("b")))
        assertEquals("a[2]", JsonPick.join("a", JsonPick.Step.Index(2)))
        assertEquals("a[\"x.y\"]", JsonPick.join("a", JsonPick.Step.Key("x.y")))
        assertEquals("[\"1st\"]", JsonPick.join("", JsonPick.Step.Key("1st")))
        // 作った道筋で同じ値に戻れる
        val path = JsonPick.join(JsonPick.join("attributes", JsonPick.Step.Key("a.b")), JsonPick.Step.Key("c"))
        assertEquals(7.0, JsonPick.number(JsonPick.pick(root, path))!!, 1e-9)
    }

    @Test
    fun `number style reads the value, unit and scale`() {
        val def = WidgetDef("w1", path = "state", unitPath = "attributes.unit_of_measurement", scale = 2.0)
        val r = WidgetRead.parse(def, ha, 1000L) as WidgetPush.Value
        assertEquals(46.9, r.data.number!!, 1e-9)
        assertEquals("°C", r.data.unit)
        assertEquals(1000L, r.data.updatedAt)
        assertEquals(def.dataKey, r.data.key)
    }

    @Test
    fun `missing path and non numbers are reported`() {
        val missing = WidgetRead.parse(WidgetDef("w1", path = "nope"), ha, 0) as WidgetPush.Bad
        assertTrue(missing.reason.contains("nope"))
        val notNumber = WidgetRead.parse(WidgetDef("w1", path = "entity_id"), ha, 0)
        assertTrue(notNumber is WidgetPush.Bad)
        assertTrue(WidgetRead.parse(WidgetDef("w1", path = "state"), "<html>", 0) is WidgetPush.Bad)
    }

    @Test
    fun `text style accepts plain text bodies`() {
        val r = WidgetRead.parse(WidgetDef("w1", style = "text"), "  hello  ", 0) as WidgetPush.Value
        assertEquals("hello", r.data.text)
    }

    @Test
    fun `list style reads arrays of objects and objects`() {
        val def = WidgetDef("w1", style = "list", path = "list", labelKey = "name", valueKey = "value", unit = "℃", warnAt = 20.0)
        val r = WidgetRead.parse(def, ha, 0) as WidgetPush.Value
        assertEquals(listOf("台所", "寝室"), r.data.items.map { it.label })
        assertEquals(listOf("21.5℃", "19℃"), r.data.items.map { it.value })
        assertEquals(listOf(WidgetLevel.WARN, WidgetLevel.OK), r.data.items.map { it.level })
        val obj = WidgetRead.parse(WidgetDef("w1", style = "list", path = "attributes"), ha, 0) as WidgetPush.Value
        assertEquals("unit_of_measurement", obj.data.items.first().label)
        // キーを決めていなければ 1 つ目と 2 つ目の項目
        val auto = WidgetRead.parse(WidgetDef("w1", style = "list", path = "list"), ha, 0) as WidgetPush.Value
        assertEquals("台所", auto.data.items.first().label)
    }

    @Test
    fun `pushed bodies in the simple forms`() {
        val def = WidgetDef("w1", source = "push", unit = "W")
        assertEquals(812.0, (WidgetRead.push(def, "{\"value\": 812}", 0) as WidgetPush.Value).data.number!!, 1e-9)
        assertEquals(812.0, (WidgetRead.push(def, "812", 0) as WidgetPush.Value).data.number!!, 1e-9)
        assertEquals("kWh", (WidgetRead.push(def, "{\"value\": 3, \"unit\": \"kWh\"}", 0) as WidgetPush.Value).data.unit)
        assertEquals("洗濯中", (WidgetRead.push(def, "洗濯中", 0) as WidgetPush.Value).data.text)
        assertEquals("on", (WidgetRead.push(def, "{\"value\": \"on\"}", 0) as WidgetPush.Value).data.text)
        val items = (WidgetRead.push(def, "{\"items\":[{\"label\":\"NAS\",\"value\":\"ok\"},{\"label\":\"PC\",\"value\":1,\"level\":\"alert\"}]}", 0) as WidgetPush.Value).data.items
        assertEquals(listOf("NAS", "PC"), items.map { it.label })
        assertEquals(listOf(WidgetLevel.OK, WidgetLevel.ALERT), items.map { it.level })
        assertTrue(WidgetRead.push(def, "  ", 0) is WidgetPush.Bad)
        assertTrue(WidgetRead.push(def, "{\"foo\": 1}", 0) is WidgetPush.Bad)
    }

    @Test
    fun `pushed bodies use the value path when set`() {
        val def = WidgetDef("w1", source = "push", path = "state", unitPath = "attributes.unit_of_measurement")
        val r = WidgetRead.push(def, ha, 0) as WidgetPush.Value
        assertEquals(23.45, r.data.number!!, 1e-9)
        assertEquals("°C", r.data.unit)
    }

    @Test
    fun `levels follow the thresholds above and below`() {
        val above = WidgetDef("w1", warnAt = 28.0, alertAt = 31.0)
        assertEquals(WidgetLevel.OK, WidgetLevel.of(27.9, above))
        assertEquals(WidgetLevel.WARN, WidgetLevel.of(28.0, above))
        assertEquals(WidgetLevel.ALERT, WidgetLevel.of(35.0, above))
        assertEquals(WidgetLevel.OK, WidgetLevel.of(null, above))
        val below = WidgetDef("w1", warnAt = 30.0, alertAt = 20.0, below = true)
        assertEquals(WidgetLevel.OK, WidgetLevel.of(45.0, below))
        assertEquals(WidgetLevel.WARN, WidgetLevel.of(25.0, below))
        assertEquals(WidgetLevel.ALERT, WidgetLevel.of(20.0, below))
        assertEquals(WidgetLevel.OK, WidgetLevel.of(5.0, WidgetDef("w1")))
    }

    @Test
    fun `numbers are formatted with grouping and sensible digits`() {
        assertEquals("1,235", WidgetFormat.number(1234.56))
        assertEquals("123.5", WidgetFormat.number(123.45))
        assertEquals("23.45", WidgetFormat.number(23.45))
        assertEquals("23.4", WidgetFormat.number(23.4))
        assertEquals("23", WidgetFormat.number(23.0))
        assertEquals("23.00", WidgetFormat.number(23.0, 2))
        assertEquals("−4.5", WidgetFormat.number(-4.5))
        assertEquals("0", WidgetFormat.number(-0.001, 0))
        assertEquals("23.4°C", WidgetFormat.withUnit("23.4", "°C"))
        assertEquals("812 W", WidgetFormat.withUnit("812", "W"))
        assertEquals("812", WidgetFormat.withUnit("812", ""))
    }

    @Test
    fun `history keeps one point per five minutes for a day`() {
        var h = emptyList<WidgetPoint>()
        val start = 1_800_000_000_000L - 1_800_000_000_000L % WIDGET_HISTORY_STEP_MS
        h = WidgetRepository.appendHistory(h, start, 1.0)
        h = WidgetRepository.appendHistory(h, start + 60_000, 2.0)
        assertEquals(1, h.size)
        assertEquals(2.0, h.last().v, 0.0)
        h = WidgetRepository.appendHistory(h, start + WIDGET_HISTORY_STEP_MS, 3.0)
        assertEquals(2, h.size)
        // 1 日より古い点は捨てる
        h = WidgetRepository.appendHistory(h, start + 25 * 3_600_000L, 4.0)
        assertEquals(listOf(4.0), h.map { it.v })
        var many = emptyList<WidgetPoint>()
        repeat(400) { many = WidgetRepository.appendHistory(many, start + it * WIDGET_HISTORY_STEP_MS, it.toDouble()) }
        assertTrue(many.size <= WIDGET_HISTORY_POINTS + 1)
    }

    @Test
    fun `sanitize fixes bad values`() {
        val bad = WidgetDef(
            "w2", source = "ftp", style = "pie", url = "file:///etc/passwd", headerName = "X Bad", headerValue = "a\nb",
            decimals = 9, scale = 0.0, min = 10.0, max = 5.0, warnAt = Double.NaN, intervalMin = 7, tone = "nope",
            targets = List(10) { WidgetTarget("t$it", "tcp://h:$it") } + WidgetTarget("x", "ssh://h"),
        )
        val s = WidgetSanitize.def(bad)
        assertEquals("http", s.source)
        assertEquals("number", s.style)
        assertEquals("", s.url)
        assertEquals("", s.headerName)
        assertNull(s.headerValue)
        assertEquals(6, s.decimals)
        assertEquals(1.0, s.scale, 0.0)
        assertEquals(110.0, s.max, 0.0)
        assertNull(s.warnAt)
        assertEquals(5, s.intervalMin)
        assertEquals("", s.tone)
        assertEquals(MAX_WIDGET_TARGETS, s.targets.size)
        val config = WidgetSanitize.config(WidgetsConfig(items = listOf(WidgetDef("w3", title = "x"), WidgetDef("w9"))))
        assertEquals(WIDGET_IDS, config.items.map { it.id })
        assertEquals("x", config.def("w3").title)
    }

    @Test
    fun `targets are read from lines`() {
        val t = WidgetSanitize.parseTargets("NAS http://192.168.1.10:5000\n\nhttps://example.com\nルーター tcp://192.168.1.1:80")
        assertEquals(listOf("NAS", "", "ルーター"), t.map { it.name })
        assertEquals("https://example.com", t[1].url)
        assertEquals("NAS http://192.168.1.10:5000\nhttps://example.com\nルーター tcp://192.168.1.1:80", WidgetSanitize.targetLines(t))
    }

    @Test
    fun `data key ignores title and thresholds`() {
        val a = WidgetDef("w1", url = "https://x", path = "a")
        assertEquals(a.dataKey, a.copy(title = "t", warnAt = 3.0, notify = true).dataKey)
        assertTrue(a.dataKey != a.copy(path = "b").dataKey)
        assertEquals("monitor", a.copy(source = "monitor", style = "number").shownStyle)
    }

    @Test
    fun `templates fill in placeholders`() {
        val loc = LocationConfig(latitude = 35.5, longitude = 139.25)
        assertEquals("https://x?lat=35.5&lon=139.25&l=en", widgetUrl("https://x?lat={lat}&lon={lon}&l={lang}", loc, "en"))
        WIDGET_TEMPLATES.forEach { t ->
            val d = WidgetSanitize.def(t.apply(WidgetDef("w1")))
            assertTrue(t.id, WidgetRepository.configured(d))
            assertTrue(t.id, JsonPick.parse(d.path) != null)
        }
    }

    @Test
    fun `saved header values are kept, cleared or dropped when the host changes`() {
        val current = WidgetSanitize.config(WidgetsConfig(items = listOf(WidgetDef("w1", url = "https://ha.example/api", headerName = "Authorization", headerValue = "Bearer old"))))
        fun after(next: WidgetDef) = WidgetSanitize.patch(current, WidgetsPatch(listOf(next))).def("w1").headerValue
        val same = WidgetDef("w1", url = "https://ha.example/other", headerName = "Authorization")
        assertEquals("Bearer old", after(same))
        assertEquals("Bearer new", after(same.copy(headerValue = " Bearer new ")))
        assertNull(after(same.copy(headerValue = "")))
        assertNull(after(same.copy(headerName = "")))
        assertNull(after(same.copy(url = "https://evil.example/api")))
        // 送られなかったカードは変えない
        assertEquals("Bearer old", WidgetSanitize.patch(current, WidgetsPatch(listOf(WidgetDef("w2", title = "x")))).def("w1").headerValue)
    }
}
