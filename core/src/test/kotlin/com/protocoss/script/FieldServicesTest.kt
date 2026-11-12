package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.field.FieldDialogue
import com.protocoss.field.FieldScript
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class FieldServicesTest {
    private val reader=JsonReader()
    private val report=reader.parse(Files.readString(Path.of("../docs/restoration/field-services-native.json")))
    private val raw=Files.readAllBytes(Path.of("../assets/data/events/area00.gm"))
    private val archive=EventArchive(raw)
    private val opening=reader.parse(Files.readString(Path.of("../assets/opening/opening.json")))
    private val indoor=reader.parse(Files.readString(Path.of("../assets/data/field/indoor-dialogues.json")))
    private val characters=(opening.get("font").getString("characters")+indoor.get("font").getString("characters")).toSet()
    private fun bytes(value:String)=Base64.getDecoder().decode(value)
    private fun hash(raw:ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    private fun context(c:JsonValue)="${c.getInt("group")}/${c.getInt("argument")}/${c.getInt("stage")}/${c.getInt("seen")}"
    private fun snapshot(machine:EventMachine,expected:JsonValue) {
        assertEquals(expected.getInt("offset"),machine.offset)
        assertEquals(expected.getLong("instructions"),machine.executedInstructions)
        val raw=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { machine.variables().forEach { putShort(it.toShort()) } }.array()
        assertEquals(expected.getString("variables"),hash(raw))
    }
    @Test fun `typed field requests and resume positions match native handlers`() {
        assertEquals(report.getString("source_sha256"),hash(raw))
        for(case in report.get("cases")) {
            val save=DosSave.parse(bytes(case.getString("before")))
            val machine=EventMachine(archive,IntArray(300) { save.variable(it) },case.getInt("argument"),true)
            for(expected in case.get("requests")) {
                val request=machine.run();snapshot(machine,expected)
                when(request) {
                    is EventMachine.Stop.Warp -> {
                        assertEquals("warp",expected.getString("kind"))
                        assertEquals(expected.get("args").asIntArray().toList(),listOf(request.map,request.scrollX,request.scrollY,request.x,request.y,request.pose,request.layer,request.music))
                        assertEquals(expected.getInt("at"),request.offset);assertSame(request,machine.run());machine.resumeService()
                    }
                    is EventMachine.Stop.Effect -> {
                        assertEquals("effect",expected.getString("kind"));assertEquals(expected.getInt("id"),request.id);machine.resumeService()
                    }
                    is EventMachine.Stop.Place -> {
                        assertEquals("place",expected.getString("kind"));assertEquals(expected.getString("text"),EventText.decode(request.bytes()))
                        assertSame(request,machine.run());machine.resumeService()
                    }
                    is EventMachine.Stop.Dialogue -> {
                        assertEquals("dialogue",expected.getString("kind"));assertContentEquals(bytes(expected.getString("raw")),request.bytes());machine.resumeDialogue()
                    }
                    else -> fail("외부 명령 종류 불일치: ${context(case)}")
                }
            }
            val end=machine.run()
            if(end is EventMachine.Stop.Door || end is EventMachine.Stop.Panel) {
                // 이 기록은 문 해석을 추가하기 전 명령 직전에서 멈춘 자료다.
                val offset=if(end is EventMachine.Stop.Door) end.offset else (end as EventMachine.Stop.Panel).offset
                assertEquals(if(end is EventMachine.Stop.Door)0xa else if((end as EventMachine.Stop.Panel).opening)0x19 else 0x18,case.get("end").getInt("opcode"))
                assertEquals(case.get("end").getInt("offset"),offset)
                assertEquals(offset+if(end is EventMachine.Stop.Door)3 else 1,machine.offset)
                assertEquals(case.get("end").getLong("instructions")+1,machine.executedInstructions)
                continue
            }
            snapshot(machine,case.get("end"))
            if(case.get("end").getString("kind")=="finished") assertIs<EventMachine.Stop.Finished>(end)
            else assertEquals(case.get("end").getInt("opcode"),assertIs<EventMachine.Stop.Unhandled>(end).opcode)
        }
    }
    @Test fun `supported field programs preserve the entire native DOS save`() {
        var accepted=0
        for(case in report.get("cases")) {
            val before=bytes(case.getString("before"));val source=DosSave.parse(before)
            val blocked=mutableListOf<FieldScript.Blocked>()
            val script=FieldScript.prepare(archive,source,case.getInt("argument"),opening.get("maps"),characters,true) { blocked+=it }
            if(case.get("end").getString("kind")=="unhandled") {
                assertNull(script,context(case));assertTrue(blocked.isNotEmpty());assertContentEquals(before,source.bytes());continue
            }
            assertNotNull(script,"${context(case)} $blocked")
            run@while(true) when(val request=script.next()) {
                is EventMachine.Stop.Dialogue -> script.resumeDialogue()
                is EventMachine.Stop.Warp -> script.warp(request)
                is EventMachine.Stop.Place -> { script.place(request);script.resumeService() }
                is EventMachine.Stop.Effect -> script.resumeService()
                is EventMachine.Stop.Finished -> break@run
                else -> fail("지원하는 필드 이벤트가 중간에 멈췄습니다.")
            }
            assertContentEquals(bytes(case.getString("after")),script.save.bytes(),context(case));accepted++
        }
        assertEquals(356,accepted)
    }
    @Test fun `live dialogue parsing preserves original extracted pages`() {
        for(case in report.get("cases")) for(request in case.get("requests")) {
            if(request.getString("kind")!="dialogue")continue
            val actual=EventText.pages(bytes(request.getString("raw")));val expected=request.get("pages")
            assertEquals(expected.size,actual.size,context(case))
            for(i in 0 until expected.size) {
                assertEquals(expected[i].getString("speaker"),actual[i].getString("speaker"))
                assertEquals(expected[i].getInt("portrait"),actual[i].getInt("portrait"))
                assertEquals(expected[i].getString("text"),actual[i].getString("text"))
            }
        }
    }
    @Test fun `consecutive native dialogue calls continue page numbering without changing source pages`() {
        fun pages(text:String)=EventText.pages(text.toByteArray(java.nio.charset.Charset.forName("x-Johab")))
        val first=pages("/[5S0카만]첫 줄/N남은 줄/D다음 줄/E")
        val second=pages("/[4이름]새 문장/E")
        val talk=FieldDialogue(2,first)
        while(!talk.finished)talk.advance()
        talk.continueWith(second)
        assertEquals(2,talk.pageIndex);assertFalse(talk.finished);assertEquals(0,talk.visibleCharacters)
        assertEquals("새 문장",talk.page.getString("text"));assertEquals(1,second.size)
        assertFailsWith<IllegalStateException> { talk.continueWith(second) }
    }
}
