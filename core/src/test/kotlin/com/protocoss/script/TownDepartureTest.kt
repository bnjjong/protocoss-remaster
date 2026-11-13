package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.field.*
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class TownDepartureTest {
    private fun json(path:String)=JsonReader().parse(Files.readString(Path.of("../$path")))
    private val report=json("docs/restoration/town-departure-native.json")
    private val opening=json("assets/opening/opening.json")
    private val archive=EventArchive(Files.readAllBytes(Path.of("../assets/data/events/area00.gm")))
    private val story=FieldStory(json("assets/data/events/story.json"),opening)
    private val videos=FieldVideos(json("assets/data/events/videos.json"))
    private val characters=(opening.get("font").getString("characters")+json("assets/data/events/departure.json").get("font").getString("characters")).toSet()
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun snapshot(script:FieldScript,row:JsonValue) {
        assertEquals(row.getInt("offset"),script.machine.offset);assertEquals(row.getLong("instructions"),script.machine.executedInstructions)
        val b=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { script.machine.variables().forEach { putShort(it.toShort()) } }.array()
        assertEquals(row.getString("variables"),MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) })
    }
    @Test fun `warnings rejoining departure movie and warp match native states and whole saves`() {
        var animations=0;var clips=0;var warps=0
        for(row in report.get("cases")) {
            val before=bytes(row.getString("before"));val source=DosSave.parse(before)
            val script=assertNotNull(FieldScript.prepare(archive,source,22,opening.get("maps"),characters,true,story=story,videos=videos),row.getString("name"))
            for(expected in row.get("requests")) {
                val request=script.next();snapshot(script,expected);assertSame(request,script.next())
                when(request) {
                    is EventMachine.Stop.Dialogue -> { assertContentEquals(bytes(expected.getString("raw")),request.bytes());script.resumeDialogue() }
                    is EventMachine.Stop.Animation -> {
                        val plan=script.beginAnimation(request,story)
                        for(call in expected.get("calls")) {
                            val child=script.child(call.getInt("argument"))
                            for(service in call.get("requests")) {
                                val stop=assertIs<EventMachine.Stop.Dialogue>(child.next());snapshot(child,service)
                                assertContentEquals(bytes(service.getString("raw")),stop.bytes());child.resumeDialogue()
                            }
                            assertIs<EventMachine.Stop.Finished>(child.next());assertContentEquals(bytes(call.getString("after")),child.save.bytes());script.adopt(child.save)
                        }
                        script.finishAnimation(request,story,plan);assertContentEquals(bytes(expected.getString("after")),script.save.bytes());animations++
                    }
                    is EventMachine.Stop.Party -> { script.changeParty(request);assertContentEquals(bytes(expected.getString("after")),script.save.bytes()) }
                    is EventMachine.Stop.Video -> {
                        val plan=videos.plan(request,script.save,false);assertEquals(464,plan.getInt("frames"))
                        assertEquals(listOf(2,30,0),listOf(request.ticks,request.music,request.restore));assertEquals(464,expected.getInt("frames"))
                        assertEquals(listOf(".\\S&W\\s0030.MFA"),expected.get("loads").map { it.asString() })
                        assertContentEquals(bytes(expected.getString("before")),script.save.bytes());script.resumeVideo(request)
                        assertContentEquals(bytes(expected.getString("after")),script.save.bytes());assertFailsWith<IllegalStateException> { script.resumeVideo(request) };clips++
                    }
                    is EventMachine.Stop.Warp -> { assertEquals(listOf(13,0,0,56,24,4,0,15),listOf(request.map,request.scrollX,request.scrollY,request.x,request.y,request.pose,request.layer,request.music));script.warp(request);warps++ }
                    is EventMachine.Stop.Place -> { assertEquals(expected.getString("text"),EventText.decode(request.bytes()));script.place(request);script.resumeService() }
                    else -> fail("출발 이벤트의 원작 명령과 다릅니다.")
                }
            }
            assertIs<EventMachine.Stop.Finished>(script.next());snapshot(script,row.get("end"));assertContentEquals(bytes(row.getString("after")),script.save.bytes(),row.getString("name"))
            assertContentEquals(before,source.bytes())
        }
        assertEquals(6,animations);assertEquals(4,clips);assertEquals(12,warps)
    }
    @Test fun `missing root video binding rejects before any rejoin flags or map effects`() {
        val row=report.get("cases").first { it.get("requests").any { r -> r.getString("kind")=="video" } };val source=DosSave.parse(bytes(row.getString("before")));val before=source.bytes()
        assertNull(FieldScript.prepare(archive,source,22,opening.get("maps"),characters,true,story=story))
        val script=FieldScript(archive,source,22)
        while(script.next() is EventMachine.Stop.Dialogue) script.resumeDialogue()
        val request=assertIs<EventMachine.Stop.Video>(script.next())
        assertFailsWith<IllegalArgumentException> { videos.plan(request,source,true) }
        assertContentEquals(before,source.bytes())
    }
    @Test fun `new movement atlas and animation metadata have the recorded dimensions and hashes`() {
        val map=opening.get("maps").get("13");assertEquals(80,map.getInt("width"));assertEquals(50,map.getInt("height"))
        val data=Files.readAllBytes(Path.of("../assets/data/field/movement-13.bin"));assertEquals(48000,data.size)
        val meta=json("assets/data/field/field.json").get("maps").get("13")
        assertEquals(meta.getString("sha256"),MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
        val rules=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) { Files.readAllBytes(Path.of("../assets/data/field/$it")) }
        for(y in 0 until 48) for(x in 0 until 78) for(layer in 0..2) for((index,d) in listOf(1,2,4,8).withIndex()) {
            val raw=data[((layer*50+y)*80+x)*4+index].toInt() and 255;val direction=raw and 15
            val tx=x*8+FieldRules.dx(direction)*8;val ty=y*8+FieldRules.dy(direction)*8
            val step=rules.step(13,x*8f,y*8f,d,layer)
            if(tx>=0 && ty>=0 && tx+24<=640 && ty+24<=400) assertEquals(FieldRules.Step(direction,raw shr 4),step)
        }
    }
}
