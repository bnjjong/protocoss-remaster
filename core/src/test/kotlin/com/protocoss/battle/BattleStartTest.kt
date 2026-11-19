package com.protocoss.battle

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class BattleStartTest {
    private fun json(p:String)=Files.readString(Path.of("../$p"))
    private val report=JsonReader().parse(json("docs/restoration/battle-start-native.json"))
    private val party=BattleParty(json("assets/battle/start.json"))
    @Test fun `initial participants poses positions copied parameters and drawing order match native`() {
        for(case in report.get("party_cases")) {
            val b=Base64.getDecoder().decode(case.getString("before"));val save=DosSave.parse(b)
            val actual=party.prepare(save);val rows=case.get("party")
            assertEquals(rows.size,actual.size)
            for((i,a) in actual.withIndex()) {
                val e=rows[i]
                assertEquals(e.getInt("character"),a.character);assertEquals(e.getInt("pose"),a.pose)
                assertEquals(e.getInt("role"),a.role);assertEquals(e.getInt("effect"),a.effect)
                assertEquals(e.get("stats").map { it.asInt() },a.stats)
                assertEquals(e.getString("parameters"),a.parameterHex)
                assertEquals(listOf(e.getInt("x"),e.getInt("y"),e.getInt("home_x"),e.getInt("home_y"),e.getInt("hud_x"),e.getInt("hud_y"),e.getInt("gauge")),
                    listOf(a.x,a.y,a.homeX,a.homeY,a.hudX,a.hudY,a.gauge))
            }
            assertEquals(case.get("layers").map { BattleParty.Layer(it.getInt("character"),it.getInt("frame"),it.getInt("x"),it.getInt("y")) },party.layers(actual))
            assertContentEquals(b,save.bytes())
        }
        assertEquals(93,report.get("party_cases").size)
    }
    private fun clock(row:JsonValue):BattleClock {
        fun actor(r:JsonValue)=BattleClock.Actor(r.getInt("status"),r.getInt("speed"),r.getInt("gauge"),r.getInt("hp",0),r.getInt("max_hp",0),r.getInt("mp",0),r.getInt("max_mp",0)).apply { charging=r.getBoolean("charging",false) }
        return BattleClock(row.get("party").map(::actor),row.get("enemies").map(::actor),party.data.get("delays").map { it.asInt() })
    }
    @Test fun `clock gating ready queues enemy priority and party selection match native sequences`() {
        for(case in report.get("clock_cases")) {
            val c=clock(case.get("initial"))
            for((i,step) in case.get("steps").withIndex()) {
                c.tick(step.getBoolean("enabled"))
                val enemy=c.nextEnemy();assertEquals(step.getInt("enemy").takeIf { it>=0 },enemy,"enemy $i")
                enemy?.let(c::finishEnemy)
                if(step.getString("op")=="refresh")c.refreshParty()
                else {
                    val selected=c.nextParty();assertEquals(step.getInt("selected").takeIf { it>=0 },selected,"party $i")
                    selected?.let(c::finishParty)
                }
                val e=step.get("after")
                assertEquals(e.getLong("time"),c.time)
                assertEquals(e.get("party_ticks").map { it.asLong() },c.party.map { it.ticks },"ticks $i")
                assertEquals(e.get("enemy_ticks").map { it.asLong() },c.enemies.map { it.ticks })
                assertEquals(e.get("party_gauge").map { it.asInt() },c.party.map { it.gauge })
                assertEquals(e.get("enemy_gauge").map { it.asInt() },c.enemies.map { it.gauge })
                assertEquals(e.get("hp_bars").map { it.asInt() },c.party.map { it.hpBar })
                assertEquals(e.get("mp_bars").map { it.asInt() },c.party.map { it.mpBar })
                assertContentEquals(e.get("queue").asIntArray(),c.queueSnapshot())
                assertEquals(e.getInt("head"),c.head);assertEquals(e.getInt("tail"),c.tail)
            }
        }
    }
    @Test fun `arrows route immediately escape does not close and submenu result controls consumption`() {
        for(case in report.get("commands")) {
            val menu=BattleCommandMenu(case.getInt("party_index"));val services=mutableListOf<String>();var escapes=0
            for(key in case.get("keys")) {
                if(menu.closed)break
                when(val request=menu.input(key.asInt())) {
                    is BattleCommandMenu.Request.Submenu -> { services+=request.direction.name.lowercase();menu.resume(case.getInt("result")!=0) }
                    is BattleCommandMenu.Request.RejectedEscape -> {
                        val sound=case.get("events").first { it.getString("kind")=="sound" }
                        val effect=case.get("events").first { it.getString("kind")=="effect" }
                        assertEquals(sound.getInt("a"),request.sound)
                        assertEquals(effect.getInt("a"),request.effect);assertEquals(effect.getInt("d"),request.animation)
                        escapes++
                    }
                    null -> Unit
                }
            }
            assertEquals(case.get("events").filter { it.getString("kind")=="service" }.map { it.getString("direction") },services)
            assertEquals(case.get("events").count { it.getString("kind")=="effect" },escapes)
            assertEquals(case.getBoolean("closed"),menu.closed)
            assertEquals(case.getInt("gauge"),menu.gauge);assertEquals(case.getInt("selected"),menu.selected);assertEquals(case.getInt("mode"),menu.mode)
        }
        val m=BattleCommandMenu();assertFailsWith<IllegalStateException> { m.resume(true) }
        assertNotNull(m.input(72));assertFailsWith<IllegalStateException> { m.input(77) };m.resume(true)
        assertFailsWith<IllegalStateException> { m.input(72) }
    }
}
