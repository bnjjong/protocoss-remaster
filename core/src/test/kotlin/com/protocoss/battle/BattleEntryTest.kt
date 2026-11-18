package com.protocoss.battle

import com.badlogic.gdx.utils.JsonReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class BattleEntryTest {
    private fun json(path: String) = Files.readString(Path.of("../$path"))
    private val report = JsonReader().parse(json("docs/restoration/first-battle-native.json"))
    private val entry = BattleEntry(json("assets/battle/first-battle.json"))
    private fun field() = assertNotNull(entry.freshField(0, 13))

    @Test fun `every encounter cell matches the native call and consumes only that cell`() {
        val native = ByteBuffer.wrap(Base64.getDecoder().decode(report.getString("outcomes"))).order(ByteOrder.LITTLE_ENDIAN)
        var encounters = 0
        for (i in 0 until entry.width * entry.height) {
            val field = field();val before = field.snapshot()
            val expected = native.short.toInt() and 65535
            val result = field.poll((i % entry.width - 1) * 8f, (i / entry.width - 2) * 8f, false, true, false)
            assertEquals(expected.takeUnless { it == 65535 }, result?.id, "cell $i")
            if (result != null) { before[i] = 65535;encounters++ }
            assertContentEquals(before, field.snapshot())
        }
        assertEquals(34, encounters)
    }

    @Test fun `native busy disabled protected scrolled subpixel and repeated entries agree`() {
        for (row in report.get("cases")) {
            val field = field();val before = field.snapshot()
            fun poll() = field.poll(row.getFloat("x"), row.getFloat("y"), row.getInt("busy") != 0,
                row.getInt("enabled") != 0, row.getInt("protected") != 0)
            val expected = row.get("first").firstOrNull()?.get(0)?.asInt()
            assertEquals(expected, poll()?.id, row.toString())
            assertNull(poll())
            val index = (row.getInt("y") / 8 + 2) * entry.width + row.getInt("x") / 8 + 1
            if (expected != null) before[index] = 65535
            assertContentEquals(before, field.snapshot())
            assertEquals(row.getInt("after"), field.snapshot()[index])
        }
        assertEquals(408, report.get("cases").size)
    }

    @Test fun `four native enemy formations preserve distinct instances positions and initial records`() {
        assertEquals(setOf(50,51,52,53), entry.formations.keys)
        val data = JsonReader().parse(json("assets/battle/first-battle.json"))
        for (row in data.get("formations")) {
            val formation = entry.formations.getValue(row.getInt("id"))
            assertEquals(23, formation.background)
            for ((i, enemy) in formation.enemies.withIndex()) {
                val native = row.get("enemies")[i]
                assertEquals(native.getInt("monster"), enemy.monster)
                assertEquals(native.getInt("x"), enemy.x);assertEquals(native.getInt("y"), enemy.y)
                assertEquals(native.get("initial_state")[3].asInt(), enemy.monster)
                assertEquals(1, native.get("initial_state")[4].asInt())
                assertEquals(1, native.get("initial_state")[5].asInt())
                assertEquals(native.get("initial_state")[6].asInt(), enemy.counter)
                assertEquals(178, enemy.parameterHex.length)
                assertEquals(0, native.get("initial_state")[7].asInt())
                assertEquals(0, native.get("initial_state")[8].asInt())
            }
        }
        assertEquals(listOf(1,2,2,3), entry.formations.values.map { it.enemies.size })
        assertEquals(listOf("SUPER FLY","WOLF"), entry.formations.getValue(52).enemies.map { it.name })
    }

    @Test fun `new field owns its cells and rejects unsupported maps and invalid coordinates`() {
        assertNull(entry.freshField(1,13));assertNull(entry.freshField(0,12))
        val first = field();val original = first.snapshot();val index = original.indexOf(50)
        val x = (index % entry.width - 1)*8f;val y = (index / entry.width - 2)*8f
        val exposed = first.snapshot();exposed.fill(65535)
        assertNotNull(first.poll(x,y,false,true,false))
        assertNull(first.poll(x,y,false,true,false))
        assertContentEquals(original, field().snapshot())
        for ((a,b) in listOf(-16f to 0f,0f to -24f,640f to 0f,0f to 400f))
            assertNull(first.poll(a,b,false,true,false))
        assertFailsWith<IllegalArgumentException> { first.poll(Float.NaN,0f,false,true,false) }
        assertFailsWith<IllegalArgumentException> { first.poll(0f,Float.POSITIVE_INFINITY,false,true,false) }
    }
}
