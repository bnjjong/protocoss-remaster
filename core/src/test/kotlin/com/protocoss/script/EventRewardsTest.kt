package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.field.FieldDialogue
import com.protocoss.field.FieldPanel
import com.protocoss.field.FieldRewards
import com.protocoss.field.FieldScript
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class EventRewardsTest {
    private val reader=JsonReader()
    private fun json(name:String)=reader.parse(Files.readString(Path.of("../$name")))
    private val report=json("docs/restoration/event-rewards-native.json")
    private val data=json("assets/ui/rewards/rewards.json")
    private val equipment=json("assets/ui/equipment/equipment.json")
    private val model=FieldRewards(equipment,data)
    private val opening=json("assets/opening/opening.json")
    private val indoor=json("assets/data/field/indoor-dialogues.json")
    private val characters=(opening.get("font").getString("characters")+indoor.get("font").getString("characters")+data.get("font").getString("characters")+
        equipment.get("rects").filter { it.name.startsWith("glyph-") }.map { it.name.substring(6).toInt().toChar() }.joinToString("")).toSet()
    private val raw=Files.readAllBytes(Path.of("../assets/data/events/area00.gm"))
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun archive(row:JsonValue)=EventArchive(if(row.has("raw")) bytes(row.getString("raw")) else raw)
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun snapshot(machine:EventMachine,expected:JsonValue) {
        assertEquals(expected.getInt("offset"),machine.offset);assertEquals(expected.getLong("instructions"),machine.executedInstructions)
        val buffer=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { machine.variables().forEach { putShort(it.toShort()) } }.array()
        assertEquals(expected.getString("variables"),hash(buffer))
    }
    @Test fun `all rewards and boundary arithmetic match native requests and entire saved state`() {
        assertEquals(report.get("sources").getString("DATA/AREA00.G&M"),hash(raw))
        var accepted=0;var rewards=0
        for(row in report.get("cases")) {
            val source=DosSave.parse(bytes(row.getString("before")));val before=source.bytes();val archive=archive(row)
            val script=FieldScript(archive,source,row.getInt("argument"),model)
            for(expected in row.get("requests")) {
                val request=script.next();assertSame(request,script.next());snapshot(script.machine,expected)
                when(request) {
                    is EventMachine.Stop.Dialogue -> { assertEquals("dialogue",expected.getString("kind"));assertContentEquals(bytes(expected.getString("raw")),request.bytes());script.resumeDialogue() }
                    is EventMachine.Stop.Panel -> {
                        assertEquals("panel",expected.getString("kind"));assertEquals(expected.getBoolean("opening"),request.opening)
                        val panel=FieldPanel(data,request.opening)
                        for(frame in expected.get("frames")) {
                            assertFalse(panel.finished)
                            assertEquals(frame.asIntArray().toList(),panel.rectangle.mapIndexed { i,v -> if(i<2)v+16 else v })
                            panel.update(1f/70f)
                        }
                        assertTrue(panel.finished);script.resumeService()
                    }
                    is EventMachine.Stop.Reward -> {
                        assertEquals("reward",expected.getString("kind"));assertEquals(expected.getInt("item"),request.item)
                        assertEquals(expected.getInt("count"),request.count);assertEquals(expected.getInt("category"),request.category)
                        val pages=script.rewardPages(request);assertEquals(expected.getString("notice"),pages[0].getString("text"))
                        script.receiveReward(request);assertContentEquals(bytes(expected.getString("credited")),script.save.bytes(),row.getString("name"))
                        script.receiveReward(request);assertContentEquals(bytes(expected.getString("credited")),script.save.bytes())
                        script.resumeService();rewards++
                    }
                    else -> fail("원작 보상 요청 종류가 다릅니다.")
                }
            }
            val end=script.next()
            if(end is EventMachine.Stop.Shop) {
                // 이 기록은 상점 인자를 읽기 직전에서 끝난다.
                assertEquals(0x10,row.get("end").getInt("opcode"));assertEquals(end.offset,row.get("end").getInt("offset"))
                assertEquals(end.offset+5,script.machine.offset)
                assertEquals(row.get("end").getLong("instructions")+1,script.machine.executedInstructions)
            } else snapshot(script.machine,row.get("end"))
            assertContentEquals(bytes(row.getString("after")),script.save.bytes())
            val blocked=mutableListOf<FieldScript.Blocked>()
            val prepared=FieldScript.prepare(archive,source,row.getInt("argument"),opening.get("maps"),characters,false,rewards=model) { blocked+=it }
            if(row.get("end").getString("kind")=="finished") { assertIs<EventMachine.Stop.Finished>(end);assertNotNull(prepared,"${row.getString("name")}: $blocked");accepted++ }
            else { assertTrue(end is EventMachine.Stop.Unhandled || end is EventMachine.Stop.Shop);assertNull(prepared);assertTrue(blocked.isNotEmpty()) }
            assertContentEquals(before,source.bytes())
        }
        assertEquals(570,accepted);assertEquals(369,rewards)
    }
    @Test fun `three reward operands remain unsigned literals including high bit values`() {
        for(row in report.get("parser")) {
            val args=row.get("input").asIntArray()
            val code=ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0xfa.toByte());putShort(3);put(0x11);args.forEach { putShort(it.toShort()) };put(0xf1.toByte())
            }.array()
            val program=ByteBuffer.allocate(code.size+8).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(code.size+2);putShort(1);putShort(0);put(code.map { (it.toInt() xor 127).toByte() }.toByteArray())
            }.array()
            val archive=EventArchive(program);val machine=EventMachine(archive,IntArray(300),0,true)
            val request=assertIs<EventMachine.Stop.Reward>(machine.run())
            assertEquals(row.get("args").asIntArray().toList(),listOf(request.item,request.count,request.category))
            assertEquals(row.getInt("advanced")+1,machine.offset-request.offset)
            val source=DosSave.parse(bytes(report.get("cases").first { it.has("raw") }.getString("before")))
            if(request.category !in 0..2) assertNotNull(FieldScript.prepare(archive,source,0,opening.get("maps"),characters,false,rewards=model))
        }
    }
    @Test fun `reward wait preserves native flag order and cannot credit twice`() {
        val row=report.get("cases").first { it.has("raw") };val source=DosSave.parse(bytes(row.getString("before")))
        val script=FieldScript(archive(row),source,7,model)
        assertFailsWith<IllegalStateException> { script.resumeDialogue() }
        assertIs<EventMachine.Stop.Panel>(script.next());script.resumeService()
        val request=assertIs<EventMachine.Stop.Reward>(script.next());script.receiveReward(request)
        val credited=script.save.bytes();assertEquals(123,script.save.variable(99))
        repeat(5) { assertSame(request,script.next());script.receiveReward(request);assertContentEquals(credited,script.save.bytes()) }
        script.resumeService();assertIs<EventMachine.Stop.Panel>(script.next());assertEquals(123,script.save.variable(99))
        script.resumeService();assertIs<EventMachine.Stop.Finished>(script.next());assertEquals(456,script.save.variable(99))
    }
    @Test fun `reward arrival and exit retain native coordinates and consume no extra dismissal`() {
        val row=report.get("cases").first { it.has("raw") };val script=FieldScript(archive(row),DosSave.parse(bytes(row.getString("before"))),7,model)
        script.next();script.resumeService();val request=assertIs<EventMachine.Stop.Reward>(script.next())
        val talk=FieldDialogue(0,script.rewardPages(request));assertEquals(318f,talk.rewardX)
        talk.update(1f/70f);assertEquals(302f,talk.rewardX)
        talk.advance();assertTrue(talk.rewardLeaving);assertEquals(14f,talk.rewardX);assertFalse(talk.finished)
        talk.advance();assertFalse(talk.finished)
        val frames=talk.page.getString("text").toByteArray(java.nio.charset.Charset.forName("x-Johab")).size/2+1
        repeat(frames-1) { talk.update(1f/70f);assertFalse(talk.finished) }
        talk.update(1f/70f);assertTrue(talk.finished)
    }
}
