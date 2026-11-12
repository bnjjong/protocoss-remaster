package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.field.FieldMovement
import com.protocoss.field.FieldRules
import com.protocoss.field.FieldScript
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class EventDoorsTest {
    private val reader=JsonReader()
    private val root=Path.of("..")
    private val report=reader.parse(Files.readString(root.resolve("docs/restoration/event-doors-native.json")))
    private val raw=Files.readAllBytes(root.resolve("assets/data/events/area00.gm"))
    private val archive=EventArchive(raw)
    private val opening=reader.parse(Files.readString(root.resolve("assets/opening/opening.json")))
    private val indoor=reader.parse(Files.readString(root.resolve("assets/data/field/indoor-dialogues.json")))
    private val characters=(opening.get("font").getString("characters")+indoor.get("font").getString("characters")).toSet()
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun rules()=FieldRules(Files.readString(root.resolve("assets/data/field/field.json"))) {
        Files.readAllBytes(root.resolve("assets/data/field/$it"))
    }
    private fun hash(raw:ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    @Test fun `door parser and suspension positions match original event calls`() {
        assertEquals(report.get("sources").getString("DATA/AREA00.G&M"),hash(raw))
        var doors=0;var locked=0
        for(case in report.get("cases")) {
            val source=DosSave.parse(bytes(case.getString("before")))
            val machine=EventMachine(archive,IntArray(300) { source.variable(it) },case.getInt("argument"),true)
            for(expected in case.get("requests")) {
                val request=machine.run()
                assertSame(request,machine.run());assertEquals(expected.getInt("offset"),machine.offset)
                assertEquals(expected.getLong("instructions"),machine.executedInstructions)
                val variables=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { machine.variables().forEach { putShort(it.toShort()) } }.array()
                assertEquals(expected.getString("variables"),hash(variables))
                when(request) {
                    is EventMachine.Stop.Door -> {
                        assertEquals("door",expected.getString("kind"));assertEquals(expected.getInt("id"),request.id)
                        assertEquals(expected.getInt("at"),request.offset);machine.resumeService();doors++
                    }
                    is EventMachine.Stop.Dialogue -> {
                        assertEquals("dialogue",expected.getString("kind"));assertContentEquals(bytes(expected.getString("raw")),request.bytes())
                        machine.resumeDialogue();locked++
                    }
                    else -> fail("문 이벤트의 원작 요청과 다릅니다.")
                }
            }
            assertIs<EventMachine.Stop.Finished>(machine.run())
            assertEquals(case.get("end").getInt("offset"),machine.offset)
            assertEquals(case.get("end").getLong("instructions"),machine.executedInstructions)
        }
        assertEquals(84,doors);assertEquals(4,locked)
    }

    @Test fun `native door tile attributes movement and full save match at all registered doors`() {
        var doors=0
        for(case in report.get("cases")) {
            val r=rules();val map=case.getInt("map");val x=case.getFloat("x");val y=case.getFloat("y");val layer=case.getInt("layer")
            val source=DosSave.parse(bytes(case.getString("before")));val original=source.bytes()
            val blocked=mutableListOf<FieldScript.Blocked>()
            val script=assertNotNull(FieldScript.prepare(archive,source,case.getInt("argument"),opening.get("maps"),characters,true,
                canOpenDoor={ it==0 && r.canOpenDoor(map,x,y,1,layer) }) { blocked+=it },blocked.toString())
            assertContentEquals(original,source.bytes());assertTrue(r.canOpenDoor(map,x,y,1,layer))
            val movement=FieldMovement(r,map,layer,2).apply { place(x,y);face(1) }
            run@while(true) when(script.next()) {
                is EventMachine.Stop.Door -> {
                    val changes=assertNotNull(r.openDoor(map,x,y,1,layer))
                    assertEquals(case.get("changes").toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json),changes.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
                    movement.finishDoor(1);script.resumeDoor();doors++
                    assertFalse(r.canOpenDoor(map,x,y,1,layer));assertNull(r.openDoor(map,x,y,1,layer))
                    assertEquals(case.getInt("pose"),movement.pose);assertEquals(case.getInt("direction"),movement.direction)
                    assertNull(r.event(map,x,y,1))
                }
                is EventMachine.Stop.Dialogue -> script.resumeDialogue()
                is EventMachine.Stop.Finished -> break@run
                else -> fail("문 이벤트의 지원 범위를 벗어났습니다.")
            }
            assertContentEquals(bytes(case.getString("after")),script.save.bytes())
            if(script.openedDoor) {
                assertNull(case.get("event_after").takeUnless { it.isNull })
                r.reset(map);assertTrue(r.canOpenDoor(map,x,y,1,layer))
                assertEquals(case.getInt("argument"),r.event(map,x,y,1))
            } else assertEquals(case.getInt("argument"),r.event(map,x,y,1))
        }
        assertEquals(84,doors)
    }

    @Test fun `door argument is an unsigned literal and unsupported modes never apply`() {
        for(row in report.get("parser")) {
            val value=row.getInt("input")
            val code=byteArrayOf(0xfa.toByte(),3,0,0xa,value.toByte(),(value shr 8).toByte(),0xf1.toByte())
            val raw=ByteBuffer.allocate(8+code.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(code.size+2);putShort(1);putShort(0);put(code.map { (it.toInt() xor 127).toByte() }.toByteArray())
            }.array()
            val archive=EventArchive(raw)
            val machine=EventMachine(archive,IntArray(300) { if(it==0) 0 else 9 },7,true)
            val request=assertIs<EventMachine.Stop.Door>(machine.run())
            assertEquals(row.getInt("id"),request.id);assertEquals(row.getInt("advanced")+1,machine.offset-request.offset)
            assertSame(request,machine.run());machine.resumeDoor();assertIs<EventMachine.Stop.Finished>(machine.run())
            val source=DosSave.parse(bytes(report.get("cases")[0].getString("before"))).withVariables(mapOf(0 to 0))
            val before=source.bytes()
            val prepared=FieldScript.prepare(archive,source,7,opening.get("maps"),characters,true,canOpenDoor={ true })
            if(value==0) assertNotNull(prepared) else assertNull(prepared)
            assertContentEquals(before,source.bytes())
            val truncated=raw.copyOf(raw.size-2)
            ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(truncated.size-6)
            assertFailsWith<EventMachine.Fault> { EventMachine(EventArchive(truncated),IntArray(300),0,true).run() }
        }
    }

    @Test fun `door capability rejects wrong position facing height and missing context without side effects`() {
        val case=report.get("cases")[0];val source=DosSave.parse(bytes(case.getString("before")));val before=source.bytes();val r=rules()
        val x=case.getFloat("x");val y=case.getFloat("y")
        assertFalse(r.canOpenDoor(0,x,y,1,0));assertFalse(r.canOpenDoor(0,x,y,4,1));assertFalse(r.canOpenDoor(0,x+8,y,1,1))
        assertNull(FieldScript.prepare(archive,source,0,opening.get("maps"),characters,true))
        assertContentEquals(before,source.bytes());assertTrue(r.canOpenDoor(0,x,y,1,1))
        assertFailsWith<IllegalStateException> { FieldScript(archive,source,0).resumeDoor() }
    }
}
