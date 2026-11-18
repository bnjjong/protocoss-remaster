package com.protocoss.battle

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class BasicAttackTest {
    private fun text(p:String)=Files.readString(Path.of("../$p"))
    private val report=JsonReader().parse(text("docs/restoration/battle-attack-native.json"))
    private val metadata=text("assets/battle/attack/attack.json")
    private val party=BattleParty(text("assets/battle/start.json"))
    private val entry=BattleEntry(text("assets/battle/first-battle.json"))
    @Test fun `BIOS seeded random values and counter wrapping match actual native functions`() {
        for(r in report.get("randoms")) {
            val random=DosBattleRandom({ r.getInt("clock") },r.getLong("counter"))
            assertEquals(r.getInt("value"),random.next(r.getInt("bound")))
            assertEquals(r.getLong("after"),random.counter)
        }
    }
    @Test fun `hit critical element armor health and luck mutations match native damage vectors`() {
        val weaponData=JsonReader().parse(metadata).get("weapons")
        for(r in report.get("damage")) {
            val a=r.get("attacker");val d=r.get("defender")
            val actor=PhysicalDamage.Attacker(a.getInt("attack"),a.getInt("magic"),a.getInt("luck"),a.getInt("buff"),
                a.get("weapons").filter { it.asInt()<83 }.map { weaponData[it.asInt()].getInt("element") })
            val victim=PhysicalDamage.Defender(d.getInt("hp"),d.getInt("armor"),d.getInt("evasion"),d.get("responses").map { it.asInt() })
            val random=DosBattleRandom({ r.getInt("clock") },r.getLong("counter"))
            assertEquals(r.getInt("value"),PhysicalDamage.strike(actor,victim,random),r.toString())
            assertEquals(r.getInt("hp"),victim.hp);assertEquals(r.getInt("luck"),actor.luck)
            assertEquals(r.getLong("after"),random.counter)
        }
    }
    @Test fun `target selection preserves live slots maximum x wrapping confirm and cancel`() {
        for(r in report.get("targets")) {
            val enemies=r.get("enemies").map { AttackTarget.Enemy(it.getInt("slot"),it.getInt("x"),it.getInt("y"),it.getInt("status"),it.getInt("cursor_x"),it.getInt("cursor_y")) }
            val target=AttackTarget(enemies);val cursors=mutableListOf<Pair<Int,Int>>()
            for(key in r.get("keys")) {
                if(target.closed)break
                cursors+=target.current.x+target.current.cursorX to target.current.y+target.current.cursorY
                target.input(key.asInt())
            }
            assertEquals(r.get("events").filter { it.has("x") }.map { it.getInt("x") to it.getInt("y") },cursors)
            assertEquals(r.get("selected").takeUnless { it.isNull }?.asInt(),target.selected)
            if(!r.getBoolean("paused"))assertEquals(r.getInt("result")==0,target.cancelled)
        }
    }
    private fun draws(value:JsonValue)=value.map {
        BasicAttack.Draw(it.getString("kind"),it.getString("sprite",""),it.getInt("x"),it.getInt("y"),it.getInt("shade",-1),it.getInt("table",-1),it.getString("text",""),it.getInt("color",80))
    }
    @Test fun `complete basic attacks match native frame states draw commands final health and RNG consumption`() {
        for(r in report.get("cases")) {
            val bytes=Base64.getDecoder().decode(r.getString("before"));val save=DosSave.parse(bytes)
            val actors=party.prepare(save);val formation=entry.formations.getValue(r.getInt("group"))
            val random=DosBattleRandom({ r.getInt("clock") },r.getLong("counter"))
            val conditions=r.get("conditions")?.map { it.asInt() }
            val selected=AttackTarget(formation.enemies.mapIndexed { i,e->AttackTarget.Enemy(i,e.x,e.y,conditions?.get(i) ?: 1,0,0) }).current.slot
            val attack=BasicAttack(metadata,actors,formation,r.getInt("party"),selected,random,r.get("health")?.map { it.asInt() },conditions,r.getInt("previous",0))
            val menu=BattleCommandMenu(r.getInt("party"));menu.input(72)
            for(row in r.get("frames").filter { it.getString("phase")!="target" }) {
                val actual=assertNotNull(attack.next());val expected=BasicAttack.Frame(row.getString("phase"),row.getInt("x"),row.getInt("y"),row.getInt("pose"),row.getInt("frame"),row.get("hp").map { it.asInt() },row.getInt("effect_frame"),draws(row.get("draws")))
                assertEquals(expected,actual)
                assertEquals(28,menu.gauge)
            }
            assertNull(attack.next());assertTrue(attack.done);assertNull(attack.next())
            val final=r.get("final")
            assertEquals(final.get("hp").map { it.asInt() },attack.hp);assertEquals(final.get("status").map { it.asInt() },attack.status)
            assertEquals(final.getInt("luck"),attack.luck);assertEquals(final.getLong("counter"),attack.counter)
            assertEquals(final.getInt("x"),attack.x);assertEquals(final.getInt("y"),attack.y);assertEquals(final.getInt("pose"),attack.currentPose)
            assertEquals(r.get("events").filter { it.getString("kind")=="sound" }.map { it.getInt("id") },attack.sounds)
            menu.resume(true);assertEquals(0,menu.gauge)
            assertContentEquals(bytes,save.bytes())
        }
    }
    @Test fun `consecutive attacks carry health dead slots random counter and the previous animation frame`() {
        val rows=report.get("cases");val seed=rows[2]
        val actors=party.prepare(DosSave.parse(Base64.getDecoder().decode(seed.getString("before"))))
        val formation=entry.formations.getValue(52);val random=DosBattleRandom({1})
        var previous:BasicAttack?=null
        for(index in listOf(2,8,9,10)) {
            val row=rows[index]
            val target=AttackTarget(formation.enemies.mapIndexed { i,e->AttackTarget.Enemy(i,e.x,e.y,previous?.status?.get(i) ?: 1,0,0) })
            target.input(57)
            val attack=BasicAttack(metadata,actors,formation,0,assertNotNull(target.selected),random,previous?.hp,previous?.status,previous?.windupEndFrame ?: 0)
            while(attack.next()!=null)Unit
            assertEquals(row.get("final").get("hp").map { it.asInt() },attack.hp)
            assertEquals(row.get("final").get("status").map { it.asInt() },attack.status)
            assertEquals(row.get("final").getLong("counter"),random.counter)
            previous=attack
        }
    }
    @Test fun `unsupported attacks reject before consuming random state or changing supplied health`() {
        val source=Base64.getDecoder().decode(report.get("cases")[2].getString("before"))
        val formation=entry.formations.getValue(52);val random=DosBattleRandom({1})
        val health=listOf(80,120);val conditions=listOf(1,1)
        for(kind in listOf("level","weapon")) {
            val bytes=source.copyOf();bytes[if(kind=="level")386 else 475]=if(kind=="level")2 else 83
            val actors=party.prepare(DosSave.parse(bytes))
            assertFailsWith<IllegalArgumentException> { BasicAttack(metadata,actors,formation,0,0,random,health,conditions) }
            assertEquals(0L,random.counter);assertEquals(listOf(80,120),health);assertEquals(listOf(1,1),conditions)
        }
        val menu=BattleCommandMenu();menu.input(72)
        val target=AttackTarget(listOf(AttackTarget.Enemy(0,0,0,1,0,0)));target.input(1)
        assertTrue(target.cancelled);menu.resume(false)
        assertFalse(menu.closed);assertEquals(28,menu.gauge);assertEquals(0L,random.counter)
    }
}
