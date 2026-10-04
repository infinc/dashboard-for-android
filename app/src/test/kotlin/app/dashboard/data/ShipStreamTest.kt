package app.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 船の位置（aisstream.io の電文の読み取り・購読の範囲）。 */
class ShipStreamTest {

    @Test
    fun `position report is read with metadata`() {
        val text = """{"Message":{"PositionReport":{"Cog":308,"Latitude":35.4,"Longitude":139.7,"Sog":12.3,"TrueHeading":511,"UserID":431000001}},
            "MessageType":"PositionReport","MetaData":{"MMSI":431000001,"ShipName":"SAKURA MARU@@@ ","latitude":35.4,"longitude":139.7}}"""
        val u = ShipStream.parse(text, 1000L) as ShipStream.Update.Position
        assertEquals(431000001L, u.ship.mmsi)
        assertEquals("SAKURA MARU", u.ship.name)
        assertEquals(308.0, u.ship.cog!!, 1e-9)
        assertNull("511 means no heading", u.ship.heading)
        assertEquals(12.3, u.ship.sog!!, 1e-9)
        assertEquals(1000L, u.ship.seenAt)
    }

    @Test
    fun `static data carries the ship type and destination`() {
        val text = """{"Message":{"ShipStaticData":{"Name":"NIPPON","Type":70,"Destination":"TOKYO","UserID":431000002}},
            "MessageType":"ShipStaticData","MetaData":{"MMSI":431000002}}"""
        val u = ShipStream.parse(text, 0L) as ShipStream.Update.Static
        assertEquals(70, u.type)
        assertEquals("TOKYO", u.destination)
        assertEquals(ShipCategory.CARGO, ShipStream.category(70))
    }

    @Test
    fun `errors and unknown messages`() {
        assertTrue(ShipStream.parse("""{"error":"Api Key Is Not Valid"}""", 0) is ShipStream.Update.Failure)
        assertNull(ShipStream.parse("""{"MessageType":"Interrogation","Message":{}}""", 0))
        assertNull(ShipStream.parse("not json", 0))
    }

    @Test
    fun `positions out of range are ignored`() {
        val text = """{"Message":{"PositionReport":{"Latitude":91,"Longitude":181}},"MessageType":"PositionReport","MetaData":{"MMSI":1}}"""
        assertNull(ShipStream.parse(text, 0))
    }

    @Test
    fun `widen and covers`() {
        val box = ShipStream.Box(35.0, 139.0, 36.0, 140.0)
        val wide = ShipStream.widen(box, 0.5)
        assertEquals(34.75, wide.south, 1e-9)
        assertEquals(140.25, wide.east, 1e-9)
        assertTrue(ShipStream.covers(wide, box))
        assertFalse(ShipStream.covers(box, wide))
    }

    @Test
    fun `subscription message has the key, box and message types`() {
        val json = ShipStream.subscription("k", ShipStream.Box(35.0, 139.0, 36.0, 200.0))
        assertTrue(json.contains("\"APIKey\":\"k\""))
        assertTrue(json.contains("[[35.0,139.0],[36.0,180.0]]"))
        assertTrue(json.contains("PositionReport"))
    }
}
