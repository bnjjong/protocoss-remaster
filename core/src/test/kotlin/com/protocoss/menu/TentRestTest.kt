package com.protocoss.menu

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.*

class TentRestTest {
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/tent-native.json")))
    @Test fun `all native rest conditions recovery records and quantity timing match`() {
        for(row in report.get("cases")) {
            val before=Base64.getDecoder().decode(report.get("fixtures")[row.getInt("fixture")].asString())
            before[160]=row.getInt("count").toByte();ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN).putShort(2346,row.getInt("permission").toShort())
            val save=DosSave.parse(before);val expected=before.copyOf()
            row.get("after").forEach { expected[it[0].asInt()]=it[1].asInt().toByte() }
            val allowed=row.getInt("permission")==1 && row.getInt("count")>0
            assertEquals(allowed,TentRecovery.canUse(save))
            if(allowed) {
                val wait=before.copyOf();row.get("at_wait").forEach { wait[it[0].asInt()]=it[1].asInt().toByte() }
                val recovered=TentRecovery.recover(save)
                assertContentEquals(wait,recovered.bytes(),"회복 ${row.getInt("fixture")}")
                assertEquals(before[160],recovered.bytes()[160])
                assertContentEquals(expected,TentRecovery.consume(recovered).bytes(),"완료 ${row.getInt("fixture")}")
                assertEquals(listOf(8),row.get("effects").map { it.asInt() })
                assertEquals(2,row.get("music").size)
            } else {
                assertFailsWith<IllegalArgumentException> { TentRecovery.recover(save) }
                assertContentEquals(before,expected)
                assertEquals(listOf(13,8),row.get("effects").map { it.asInt() })
                assertEquals(0,row.get("music").size)
            }
            assertEquals(save.variable(297),row.getInt("step"))
        }
    }
    @Test fun `palette volume phases wait indefinitely for a fresh key and pause freezes them`() {
        val phases=mutableListOf<TentRest.Phase>();val state=TentRest { phases+=it }
        val levels=mutableListOf<Int>();val volumes=mutableListOf<Int>()
        fun fade() { repeat(65) { levels+=state.level;volumes+=state.level*127/64;state.update(1f/70f) } }
        state.continueRest();assertEquals(TentRest.Phase.MENU_OUT,state.phase)
        fade();assertEquals(TentRest.Phase.SLEEP_IN,state.phase)
        state.continueRest();assertEquals(TentRest.Phase.SLEEP_IN,state.phase)
        state.paused=true;state.update(60f);assertEquals(0,state.level);state.paused=false
        fade();assertEquals(TentRest.Phase.WAIT,state.phase)
        state.update(120f);assertEquals(TentRest.Phase.WAIT,state.phase)
        state.paused=true;state.continueRest();assertEquals(TentRest.Phase.WAIT,state.phase);state.paused=false
        state.continueRest();fade();assertEquals(TentRest.Phase.RETURN_IN,state.phase)
        fade();assertEquals(TentRest.Phase.DONE,state.phase)
        assertEquals(report.get("trace").filter { it.getString("kind")=="palette" }.map { it.getInt("level") },levels)
        assertEquals(report.get("trace").filter { it.getString("kind")=="volume" }.map { it.getInt("level") },volumes)
        assertEquals(TentRest.Phase.entries.drop(1),phases)
    }
}
