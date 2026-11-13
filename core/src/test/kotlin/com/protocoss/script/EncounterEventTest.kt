package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.field.*
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class EncounterEventTest {
    private fun json(path:String)=JsonReader().parse(Files.readString(Path.of("../$path")))
    private val opening=json("assets/opening/opening.json")
    private val report=json("docs/restoration/encounter-event-native.json")
    private val archive=EventArchive(Files.readAllBytes(Path.of("../assets/data/events/area00.gm")))
    private val story=FieldStory(json("assets/data/events/story.json"),opening)
    private val videos=FieldVideos(json("assets/data/events/videos.json"))
    private val characters=(opening.get("font").getString("characters")+json("assets/data/field/indoor-dialogues.json").get("font").getString("characters")).toSet()
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun program(payload:ByteArray):EventArchive {
        val code=byteArrayOf(0xfa.toByte(),3,0,0x1a)+payload+byteArrayOf(0xf2.toByte(),99,0,(-56).toByte(),1,0xf1.toByte())
        return EventArchive(ByteBuffer.allocate(8+code.size).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(code.size+2);putShort(1);putShort(0);put(code.map { (it.toInt() xor 127).toByte() }.toByteArray()) }.array())
    }
    @Test fun `video name and three unsigned literals match the original parser and wait until acknowledged`() {
        for(row in report.get("parser")) {
            val machine=EventMachine(program(bytes(row.getString("input"))),IntArray(300),7,true)
            val request=assertIs<EventMachine.Stop.Video>(machine.run())
            assertContentEquals(bytes(row.getString("raw")),request.bytes())
            assertEquals(row.get("args").asIntArray().toList(),listOf(request.ticks,request.music,request.restore))
            assertEquals(4+row.getInt("advanced"),machine.offset)
            assertSame(request,machine.run());assertEquals(0,machine.variables()[99])
            val copy=request.bytes();copy.fill(0);assertContentEquals(bytes(row.getString("raw")),request.bytes())
            machine.resumeService();assertIs<EventMachine.Stop.Finished>(machine.run());assertEquals(456,machine.variables()[99])
        }
    }
    @Test fun `encounter approach movie dialogue and entire saves match the original nested functions`() {
        val rules=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) { Files.readAllBytes(Path.of("../assets/data/field/$it")) }
        for(row in report.get("cases")) {
            val before=bytes(row.getString("before"));val source=DosSave.parse(before)
            val script=assertNotNull(FieldScript.prepare(archive,source,2,opening.get("maps"),characters,true,story=story,videos=videos))
            for(expected in row.get("requests")) {
                val request=assertIs<EventMachine.Stop.Animation>(script.next())
                val proof=row.get("approach");val start=proof.get("start").asIntArray();val npcs=FieldNpcs(opening.get("maps").get("3"))
                val walk=FieldApproach(start[0],start[1],start[2],start[3],0,0,104,48,0,0,0,0,1,
                    { x,y,d,l -> rules.step(3,x.toFloat(),y.toFloat(),d,l) },{ x,y -> npcs.tick(x.toFloat(),y.toFloat()) })
                for(frame in proof.get("frames")) {
                    walk.update(1f/70f);assertEquals(frame.get("hero").asIntArray().toList(),listOf(walk.x,walk.y,walk.pose,walk.layer,walk.direction))
                }
                assertTrue(walk.finished)
                script.adopt(script.save.travel(3,0,0,walk.x+16,walk.y+16,walk.pose,walk.layer,emptyMap()))
                val plan=script.beginAnimation(request,story)
                for(call in expected.get("calls")) {
                    val child=script.child(call.getInt("argument"))
                    for(service in call.get("requests")) {
                        when(val stop=child.next()) {
                            is EventMachine.Stop.Video -> {
                                assertEquals("video",service.getString("kind"));videos.plan(stop,child.save)
                                assertContentEquals(bytes(service.getString("before")),child.save.bytes())
                                assertEquals(25,service.getInt("frames"));assertEquals(listOf(".\\S&W\\s0027.MFA",".\\S&W\\s0025.MFA"),service.get("loads").map { it.asString() })
                                child.resumeVideo(stop);assertContentEquals(bytes(service.getString("after")),child.save.bytes())
                                assertFailsWith<IllegalStateException> { child.resumeVideo(stop) }
                            }
                            is EventMachine.Stop.Dialogue -> { assertContentEquals(bytes(service.getString("raw")),stop.bytes());child.resumeDialogue() }
                            else -> fail("조우 중첩 명령이 다릅니다.")
                        }
                    }
                    assertIs<EventMachine.Stop.Finished>(child.next());assertContentEquals(bytes(call.getString("after")),child.save.bytes());script.adopt(child.save)
                }
                script.finishAnimation(request,story,plan);assertContentEquals(bytes(expected.getString("after")),script.save.bytes())
            }
            assertIs<EventMachine.Stop.Finished>(script.next());assertContentEquals(bytes(row.getString("after")),script.save.bytes())
            assertContentEquals(before,source.bytes())
        }
    }
    @Test fun `missing video bindings and unsupported arguments refuse before any state changes`() {
        val row=report.get("cases")[0];val before=bytes(row.getString("before"));val source=DosSave.parse(before)
        assertNull(FieldScript.prepare(archive,source,2,opening.get("maps"),characters,true,story=story))
        val parser=report.get("parser")[1]
        val unsupported=FieldScript(program(bytes(parser.getString("input"))),source.withVariables(mapOf(0 to 0)),7)
        assertFailsWith<IllegalStateException> { videos.plan(assertIs<EventMachine.Stop.Video>(unsupported.next()),source) }
        val valid=bytes(report.get("parser")[0].getString("input"))
        for(offset in listOf(7,9,11)) {
            val payload=valid.copyOf();payload[offset]=127
            val script=FieldScript(program(payload),source.withVariables(mapOf(0 to 0)),7)
            assertFailsWith<IllegalArgumentException> { videos.plan(assertIs<EventMachine.Stop.Video>(script.next()),source) }
        }
        assertNull(FieldScript.prepare(program(valid),source.withVariables(mapOf(0 to 0)),7,opening.get("maps"),characters,true,story=story,videos=videos))
        assertContentEquals(before,source.bytes())
    }
}
