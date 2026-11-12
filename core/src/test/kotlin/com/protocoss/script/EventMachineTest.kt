package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class EventMachineTest {
    private val reference=JsonReader().parse(Files.readString(Path.of("../docs/restoration/event-machine-native.json")))
    private val archives=(0..5).associateWith { area ->
        val raw=Files.readAllBytes(Path.of("../assets/data/events/area${area.toString().padStart(2,'0')}.gm"))
        assertEquals(reference.get("sources").getString("DATA/AREA${area.toString().padStart(2,'0')}.G&M"),hash(raw))
        EventArchive(raw)
    }
    private fun hash(raw:ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    private fun variables(fill:Int,group:Int,overrides:JsonValue?):IntArray = IntArray(300) { fill }.apply {
        this[0]=group;this[1]=0x1234;this[99]=0x5aa5;this[299]=0xa55a
        overrides?.forEach { this[it.name.toInt()]=it.asInt() }
    }
    private fun snapshot(machine:EventMachine,expected:JsonValue,context:String) {
        assertEquals(expected.getInt("offset"),machine.offset,context)
        assertEquals(expected.get("returns").asIntArray().toList(),machine.returnAddresses(),context)
        assertEquals(expected.getLong("instructions"),machine.executedInstructions,context)
        val raw=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { machine.variables().forEach { putShort(it.toShort()) } }.array()
        assertEquals(expected.getString("variables"),hash(raw),context)
    }
    private fun runCase(case:JsonValue,synthetic:Boolean=false,slice:Int=10000) {
        val archive=if(synthetic) EventArchive(Base64.getDecoder().decode(case.getString("raw"))) else archives.getValue(case.getInt("area"))
        val context=if(synthetic) case.getString("name") else "${case.getInt("area")}/${case.getInt("group")}/${case.getInt("argument")}/${case.getInt("fill")}"
        val initial=variables(case.getInt("fill",0),case.getInt("group",0),case.get("overrides"))
        val machine=EventMachine(archive,initial,case.getInt("argument",7))
        val budget=case.getInt("budget",10000);var dialogues=0
        val end=case.get("end")
        try {
            while(true) {
                val remaining=budget-machine.executedInstructions.toInt()
                val stop=if(remaining==0) EventMachine.Stop.Budget(machine.offset) else machine.run(minOf(slice,remaining))
                when(stop) {
                    is EventMachine.Stop.Dialogue -> {
                        val expected=case.get("dialogues")[dialogues++]
                        snapshot(machine,expected,context)
                        assertEquals(expected.getInt("at"),stop.offset,context)
                        assertContentEquals(Base64.getDecoder().decode(reference.get("texts").getString(expected.getString("text"))),stop.bytes(),context)
                        assertSame(stop,machine.run(1),context)
                        snapshot(machine,expected,context)
                        case.get("changes")?.get(dialogues.toString())?.forEach { machine.setVariable(it.name.toInt(),it.asInt()) }
                        machine.resumeDialogue()
                    }
                    is EventMachine.Stop.Budget -> if(machine.executedInstructions>=budget) { assertEquals("budget",end.getString("kind"),context);break }
                    is EventMachine.Stop.Finished -> {
                        assertEquals("finished",end.getString("kind"),context)
                        assertEquals(end.getString("reason"),stop.reason.name,context);assertSame(stop,machine.run(),context);break
                    }
                    is EventMachine.Stop.Unhandled -> {
                        assertEquals("unhandled",end.getString("kind"),context)
                        assertEquals(end.getInt("opcode"),stop.opcode,context);assertSame(stop,machine.run(),context)
                        assertFailsWith<IllegalStateException> { machine.resumeDialogue() };break
                    }
                    else -> error("필드 명령을 끈 검사에서 외부 요청이 발생했습니다: $context")
                }
            }
            assertTrue(end.getString("kind") !in setOf("fault","guard"),context)
        } catch(fault:EventMachine.Fault) {
            assertTrue(end.getString("kind") in setOf("fault","guard"),"$context: ${fault.message}")
            assertEquals(end.getInt("offset"),fault.offset,context)
            assertEquals(end.getInt("opcode"),fault.opcode,context)
        }
        assertEquals(case.get("dialogues").size,dialogues,context)
        snapshot(machine,end,context)
    }

    @Test fun `all areas execute pure commands and stop at the same native boundaries`() {
        reference.get("cases").forEach { runCase(it) }
    }
    @Test fun `arithmetic unsigned comparisons calls faults and dialogue resume match native code`() {
        reference.get("synthetic").forEach { runCase(it,true) }
    }
    @Test fun `one instruction slices preserve pending dialogue variables and return stack`() {
        reference.get("synthetic").filter {
            it.getString("name").startsWith("compare-0-") || it.getString("name") in setOf("nested-call-dialogue","dialogue-host-variable","jump-budget","call-limit-3")
        }.forEach { runCase(it,true,1) }
        reference.get("cases").filter { it.get("dialogues").size>0 }.take(24).forEach { runCase(it,false,1) }
    }

    private fun malformed(vararg body:Int):EventArchive {
        val code=ByteBuffer.allocate(11+body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xf0.toByte());putShort(1);putShort(7);putShort(10);put(0xfa.toByte());putShort((10+body.size).toShort())
            body.forEach { put(it.toByte()) };put(0xf1.toByte())
        }.array()
        val raw=ByteBuffer.allocate(6+16+code.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(16+code.size);putShort(8);put(ByteArray(16));put(code.map { (it.toInt() xor 127).toByte() }.toByteArray())
        }.array()
        return EventArchive(raw)
    }
    @Test fun `invalid operands fail at their instruction without consuming or mutating state`() {
        for(code in listOf(intArrayOf(0xf2,0x2c,0x81,1,0),intArrayOf(0xfa,255,255),intArrayOf(0xf9,1,0,6,1,0),intArrayOf(3,65))) {
            val original=variables(0,0,null)
            val machine=EventMachine(malformed(*code),original,7)
            val expected=original.copyOf().apply { this[1]=7 }
            val fault=assertFailsWith<EventMachine.Fault> { machine.run() }
            assertEquals(10,fault.offset);assertEquals(code[0],fault.opcode);assertEquals(10,machine.offset)
            assertContentEquals(expected,machine.variables());assertContentEquals(variables(0,0,null),original)
        }
        val machine=EventMachine(malformed(0xfb,0xf2,4,0,1,0),variables(0,0,null),7)
        val stop=assertIs<EventMachine.Stop.Unhandled>(machine.run())
        assertEquals(10,stop.offset);assertEquals(0xfb,stop.opcode);assertEquals(0,machine.variables()[4])
        assertSame(stop,machine.run());assertEquals(10,machine.offset)
    }
}
