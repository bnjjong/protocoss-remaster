package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.field.*
import com.protocoss.opening.OpeningTimeline
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class EventStoryTest {
    private val reader=JsonReader()
    private fun json(path:String)=reader.parse(Files.readString(Path.of("../$path")))
    private val report=json("docs/restoration/event-story-native.json")
    private val raw=Files.readAllBytes(Path.of("../assets/data/events/area00.gm"))
    private val archive=EventArchive(raw)
    private val opening=json("assets/opening/opening.json")
    private val data=json("assets/data/events/story.json")
    private val story=FieldStory(data,opening)
    private val characters=(opening.get("font").getString("characters")+json("assets/data/field/indoor-dialogues.json").get("font").getString("characters")).toSet()
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun snapshot(machine:EventMachine,row:JsonValue) {
        assertEquals(row.getInt("offset"),machine.offset);assertEquals(row.getLong("instructions"),machine.executedInstructions)
        val raw=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { machine.variables().forEach { putShort(it.toShort()) } }.array()
        assertEquals(row.getString("variables"),hash(raw))
    }
    private fun execute(script:FieldScript,row:JsonValue) {
        for(expected in row.get("requests")) {
            val request=script.next();snapshot(script.machine,expected);assertSame(request,script.next())
            when(request) {
                is EventMachine.Stop.Animation -> {
                    assertEquals("animation",expected.getString("kind"));assertEquals(listOf(expected.getInt("id"),expected.getInt("mode"),expected.getInt("music")),listOf(request.id,request.mode,request.music))
                    val plan=script.beginAnimation(request,story)
                    assertEquals(plan.events.values.toList(),expected.get("calls").map { it.getInt("argument") })
                    for(call in expected.get("calls")) {
                        val child=script.child(call.getInt("argument"));execute(child,call);script.adopt(child.save)
                    }
                    script.finishAnimation(request,story,plan)
                    assertContentEquals(bytes(expected.getString("after")),script.save.bytes())
                }
                is EventMachine.Stop.Party -> {
                    assertEquals("party",expected.getString("kind"));assertEquals(expected.getBoolean("joining"),request.joining);assertEquals(expected.getInt("character"),request.character)
                    script.changeParty(request);assertContentEquals(bytes(expected.getString("after")),script.save.bytes())
                }
                is EventMachine.Stop.Dialogue -> { assertEquals("dialogue",expected.getString("kind"));assertContentEquals(bytes(expected.getString("raw")),request.bytes());script.resumeDialogue() }
                is EventMachine.Stop.Warp -> {
                    assertEquals("warp",expected.getString("kind"));assertEquals(expected.get("args").asIntArray().toList(),listOf(request.map,request.scrollX,request.scrollY,request.x,request.y,request.pose,request.layer,request.music));script.warp(request)
                }
                is EventMachine.Stop.Effect -> { assertEquals("effect",expected.getString("kind"));script.resumeService() }
                is EventMachine.Stop.Place -> { assertEquals("place",expected.getString("kind"));script.place(request);script.resumeService() }
                else -> fail("스토리 명령 종류가 다릅니다.")
            }
        }
        assertIs<EventMachine.Stop.Finished>(script.next());snapshot(script.machine,row.get("end"))
        assertContentEquals(bytes(row.getString("after")),script.save.bytes())
    }
    @Test fun `story callbacks party changes and entire saves match original animation and VM functions`() {
        assertEquals(report.getString("source_sha256"),hash(raw))
        for(row in report.get("cases")) {
            val source=DosSave.parse(bytes(row.getString("before")));val before=source.bytes()
            val blocked=mutableListOf<FieldScript.Blocked>()
            val prepared=FieldScript.prepare(archive,source,row.getInt("argument"),opening.get("maps"),characters,true,story=story) { blocked+=it }
            execute(assertNotNull(prepared,"${row.getString("name")}: $blocked"),row)
            assertContentEquals(before,source.bytes())
        }
    }
    @Test fun `all eleven party records match native healing form energy return overflow and refusal`() {
        var valid=0;var refused=0
        for(row in report.get("party")) {
            val source=DosSave.parse(bytes(row.getString("before")));val before=source.bytes();val a=EventArchive(bytes(row.getString("raw")))
            val script=FieldScript.prepare(a,source,7,opening.get("maps"),characters,true)
            if(row.has("error")) {
                assertNull(script);assertFailsWith<IllegalArgumentException> { FieldParty.change(source,row.getInt("character"),row.getBoolean("joining")) };refused++
            } else { execute(assertNotNull(script),row);valid++ }
            assertContentEquals(before,source.bytes())
        }
        assertEquals(73,valid);assertEquals(37,refused)
    }
    @Test fun `animation and party arguments are unsigned literals without variable indirection`() {
        for(row in report.get("parser")) {
            val args=row.get("input").asIntArray();val op=row.getInt("opcode")
            val code=ByteBuffer.allocate(5+args.size*2).order(ByteOrder.LITTLE_ENDIAN).apply { put(0xfa.toByte());putShort(3);put(op.toByte());args.forEach { putShort(it.toShort()) };put(0xf1.toByte()) }.array()
            val raw=ByteBuffer.allocate(8+code.size).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(code.size+2);putShort(1);putShort(0);put(code.map { (it.toInt() xor 127).toByte() }.toByteArray()) }.array()
            val machine=EventMachine(EventArchive(raw),IntArray(300),0,true);val request=machine.run()
            val actual=when(request) { is EventMachine.Stop.Animation->listOf(request.id,request.mode,request.music);is EventMachine.Stop.Party->listOf(request.character);else->error("잘못된 인자 종류") }
            assertEquals(row.get("args").asIntArray().toList(),actual);assertEquals(3+row.getInt("advanced")+1,machine.offset)
        }
    }
    @Test fun `unsupported animation mode and later party refusal leave the input save untouched`() {
        val row=report.get("cases").first { it.get("requests").any { r -> r.getString("kind")=="animation" && r.getInt("id")==5 } }
        val before=bytes(row.getString("before"));val source=DosSave.parse(before)
        val request=row.get("requests").first { it.getString("kind")=="animation" }
        val changed=raw.copyOf();val count=(raw[4].toInt() and 255) or ((raw[5].toInt() and 255) shl 8)
        val mode=6+count*2+request.getInt("at")+3
        changed[mode]=(1 xor 127).toByte();changed[mode+1]=127
        assertNull(FieldScript.prepare(EventArchive(changed),source,row.getInt("argument"),opening.get("maps"),characters,true,story=story))
        assertContentEquals(before,source.bytes())
        val full=before.copyOf();repeat(5) { full[347+it*153]=1 }
        val party=DosSave.parse(full)
        assertNull(FieldScript.prepare(archive,party,row.getInt("argument"),opening.get("maps"),characters,true,story=story))
        assertContentEquals(full,party.bytes())
    }

    @Test fun `external animation dialogue waits for the child VM and preserves checkpoint IDs`() {
        val row=report.get("cases").first { it.get("requests").any { r -> r.getString("kind")=="animation" } }
        val source=DosSave.parse(bytes(row.getString("before")));val script=FieldScript(archive,source,row.getInt("argument"));val request=assertIs<EventMachine.Stop.Animation>(script.next())
        val plan=script.beginAnimation(request,story);val timeline=OpeningTimeline(opening,plan.program,plan.program,source.map,plan.events)
        var callbacks=0
        repeat(3000) {
            if(!timeline.finished) {
                if(timeline.eventArgument!=null) {
                    val argument=timeline.eventArgument!!;val id=timeline.dialogueId
                    val child=script.child(argument)
                    while(true) {
                        when(val stop=child.next()) {
                            is EventMachine.Stop.Dialogue -> {
                                timeline.provideDialogue(EventText.pages(stop.bytes()))
                                val text=timeline.page!!.getString("text");timeline.update(1f/70f);val visible=timeline.visibleCharacters
                                timeline.update(0f);assertEquals(visible,timeline.visibleCharacters);assertEquals(id,timeline.dialogueId)
                                while(!timeline.eventDialogueFinished) timeline.advance()
                                assertEquals(argument,timeline.eventArgument);assertNull(timeline.page);assertTrue(text.isNotEmpty())
                                child.resumeDialogue()
                            }
                            is EventMachine.Stop.Finished -> break
                            else -> fail("연출 자식 이벤트가 예상과 다릅니다.")
                        }
                    }
                    script.adopt(child.save);timeline.resumeEvent();callbacks++
                } else timeline.update(1f/70f)
            }
        }
        assertTrue(timeline.finished);assertEquals(2,callbacks)
        script.finishAnimation(request,story,plan)
    }
}
