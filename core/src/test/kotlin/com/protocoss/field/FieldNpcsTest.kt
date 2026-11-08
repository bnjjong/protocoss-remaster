package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class FieldNpcsTest {
    private val data=JsonReader().parse(Files.readString(Path.of("../assets/opening/opening.json")))
    private val reference=JsonReader().parse(Files.readString(Path.of("../docs/npc-native-verification.json")))
    private fun npcs(map:Int)=FieldNpcs(data.get("maps").get(map.toString()))
    private fun state(a:FieldNpcs.Actor)=listOf(a.x,a.y,a.tileX,a.tileY,a.direction,a.frame,a.mode)
    @Test fun `native motion includes interpolation edges random turns and hero blocking`() {
        for(case in reference.get("motion")) {
            val npcs=npcs(case.getInt("map"));val hero=case.get("hero")
            val hash=MessageDigest.getInstance("SHA-256")
            val checkpoints=case.get("checkpoints").associateBy { it.getInt("tick") }
            for(tick in 1..case.getInt("ticks")) {
                npcs.tick(hero[0].asFloat(),hero[1].asFloat())
                val actual=npcs.actors.map(::state)
                checkpoints[tick]?.let { checkpoint ->
                    assertEquals(checkpoint.get("actors").map { it.asIntArray().toList() },actual,"맵 ${case.getInt("map")}, 틱 $tick")
                }
                hash.update(ByteBuffer.allocate(actual.size*28).order(ByteOrder.LITTLE_ENDIAN).apply {
                    actual.forEach { actor -> actor.forEach { putInt(it) } }
                }.array())
            }
            assertEquals(case.getString("sha256"),hash.digest().joinToString("") { "%02x".format(it) })
        }
    }
    @Test fun `native player collision uses current target grid including asymmetric south edge`() {
        val npcs=mapOf(4 to npcs(4),12 to npcs(12))
        for(case in reference.get("collision")) {
            assertEquals(case[4].asBoolean(),npcs.getValue(case[0].asInt()).blocksPlayer(case[1].asFloat(),case[2].asFloat(),case[3].asInt()),case.toString())
        }
    }
    @Test fun `native talk targets respect facing height and intervening walls`() {
        val npcs=mapOf(4 to npcs(4),12 to npcs(12))
        for(case in reference.get("targets")) {
            assertEquals(case[5].asInt().takeIf { it>=0 },npcs.getValue(case[0].asInt()).talkTarget(case[1].asFloat(),case[2].asFloat(),case[3].asInt(),case[4].asInt()),case.toString())
        }
    }
    @Test fun `native approach faces hero and restores direction frame and target after talk`() {
        for(case in reference.get("approaches")) {
            val npcs=npcs(case.getInt("map"));val index=case.getInt("index");val hero=case.get("hero")
            val before=npcs.actors[index].let { it.direction to it.frame }
            npcs.beginTalk(index,case.getInt("pose"))
            for(expected in case.get("states")) {
                npcs.tick(hero[0].asFloat(),hero[1].asFloat())
                assertEquals(expected.asIntArray().toList(),state(npcs.actors[index]),case.toString())
            }
            assertTrue(npcs.talkReady)
            npcs.endTalk()
            val actor=npcs.actors[index]
            assertEquals(before,actor.direction to actor.frame);assertEquals(0,actor.mode)
            assertEquals(actor.x/8,actor.tileX);assertEquals(actor.y/8,actor.tileY)
        }
    }
    @Test fun `dialogue branches match native selected text and preserve progress`() {
        for(case in reference.get("dialogues")) {
            val pages=assertNotNull(FieldDialogue.pages(data,case.getInt("event"),case.getInt("stage")))
            val native=case.get("result").get("events")
            assertTrue(native.all { it.getString("kind")=="dialogue" })
            val text=pages.map { it.getString("text") }.joinToString(" ").replace("\n","")
            for(event in native) {
                val nativeChunks=event.getString("text").split(Regex("/\\[[^]]+]|/[NDE]"))
                // /D는 마지막 줄을 다음 페이지에 남긴다. 모든 원문 조각이 페이지에 있어야 한다.
                for(chunk in nativeChunks) assertTrue(text.contains(chunk),"누락된 원문: $chunk")
            }
            assertEquals(case.getInt("stage"),case.get("result").get("variables").getInt("8"))
        }
    }
    @Test fun `moving npc frees old footprint while blocking new target`() {
        val npcs=npcs(4)
        assertTrue(npcs.blocksPlayer(776f,464f,2))
        repeat(16) { npcs.tick(72f,8f) }
        assertFalse(npcs.blocksPlayer(776f,464f,2))
        assertTrue(npcs.blocksPlayer(784f,464f,2))
        val rules=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) { Files.readAllBytes(Path.of("../assets/data/field/$it")) }
        assertEquals(2,rules.step(4,776f,464f,2,0).direction)
        val movement=FieldMovement(rules,4,0).apply { place(784f,464f);this.npcs=npcs }
        movement.update(1f,2,true) { false };assertEquals(784f,movement.x)
    }
    @Test fun `dialogue text waits and keeps continued line on next page`() {
        val pages=assertNotNull(FieldDialogue.pages(data,14,0));val talk=FieldDialogue(1,pages)
        talk.update(1f/70f);assertEquals(1,talk.visibleCharacters)
        talk.advance();assertTrue(talk.pageComplete);assertEquals(0,talk.pageIndex)
        talk.advance();assertEquals(1,talk.pageIndex);assertEquals(0,talk.visibleCharacters)
        while(!talk.finished) { talk.advance() }
        assertEquals(pages.size-1,talk.pageIndex)
    }
}
