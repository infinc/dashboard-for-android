package app.dashboard.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TsunamiCoastTest {

    // 南北にまっすぐな架空の海岸線（経度 135.0、北緯 33.0〜33.8）を持つ予報区 "100" と、遠くの "800"
    private val coasts = TsunamiCoast.parse(
        Json.parseToJsonElement(
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[[[135.0,33.0],[135.0,33.8]]]},"properties":{"code":"100"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.6,26.1],[127.9,26.6]]},"properties":{"code":"800"}}
            ]}
            """.trimIndent(),
        ),
    )

    @Test
    fun `海岸線の近くの地点はその予報区`() {
        // 海岸線から西へ約 5 km
        assertEquals(setOf("100"), TsunamiCoast.nearby(coasts, 33.4, 134.946))
    }

    @Test
    fun `内陸の地点はどの予報区でもない`() {
        // 海岸線から西へ約 40 km
        assertEquals(emptySet<String>(), TsunamiCoast.nearby(coasts, 33.4, 134.57))
    }

    @Test
    fun `線の端より先は端までの距離で測る`() {
        // 海岸線の北の端（33.8）から北へ約 22 km
        assertEquals(emptySet<String>(), TsunamiCoast.nearby(coasts, 34.0, 135.0))
    }
}
