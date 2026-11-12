package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class IndoorDialoguesTest {
    private val model=IndoorDialogues(JsonReader().parse(Files.readString(Path.of("../assets/data/field/indoor-dialogues.json"))))
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/indoor-native.json")))
    private fun save(s:String)=DosSave.parse(Base64.getDecoder().decode(s))
    private fun money(s:DosSave)=ByteBuffer.wrap(s.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(119)
    @Test fun `all original indoor branches preserve full saves and source dialogue`() {
        for(row in report.get("cases")) {
            val before=save(row.getString("before"));val event=row.getInt("event")
            val conversation=assertNotNull(model.begin(before,event));var result=before.calledEvent(event)
            repeat(3) { result=conversation.receive(result) }
            result=conversation.finish(result)
            assertContentEquals(save(row.getString("after")).bytes(),result.bytes(),"${row.getInt("map")}/$event/${row.getInt("stage")}/${row.getInt("seen")}")
            val text=conversation.pages.map { it.getString("text") }.joinToString(" ").replace("\n","")
            for(e in row.get("events").filter { it.getString("kind")=="dialogue" })
                for(chunk in e.getString("text").split(Regex("/\\[[^]]+]|/[NDE]"))) assertTrue(text.contains(chunk),chunk)
            assertEquals(row.get("events").count { it.getString("kind")=="item" },conversation.pages.count { it.getString("kind","")=="money" })
        }
    }
    @Test fun `native gold cap and signed overflow match without changing other records`() {
        for(row in report.get("bounds")) {
            val before=save(row.getString("before"));val conversation=assertNotNull(model.begin(before,1))
            val result=conversation.finish(conversation.receive(before.calledEvent(1)))
            assertContentEquals(save(row.getString("after")).bytes(),result.bytes(),money(before).toString())
        }
    }
    @Test fun `reward is credited once and claimed flag is written after dismissal`() {
        val before=save(report.get("bounds")[2].getString("before"));val conversation=assertNotNull(model.begin(before,1))
        assertEquals(0,before.variable(50));assertEquals(0,money(before))
        val received=conversation.receive(before)
        assertEquals(50,money(received));assertEquals(0,received.variable(50))
        assertContentEquals(received.bytes(),conversation.receive(received).bytes())
        val done=conversation.finish(received);assertEquals(1,done.variable(50))
        assertFalse(assertNotNull(model.begin(done,1)).reward)
        val dialogue=FieldDialogue(0,conversation.pages)
        while(!dialogue.isReward) { dialogue.update(2f);dialogue.advance() }
        assertTrue(dialogue.isMoneyReward);assertEquals(334f,dialogue.rewardX)
        dialogue.update(1f/70f);assertEquals(318f,dialogue.rewardX)
        dialogue.update(2f);assertTrue(dialogue.pageComplete);assertFalse(dialogue.finished)
        dialogue.advance();assertTrue(dialogue.finished)
    }
    @Test fun `map group and event jointly select NPCs and do not activate furniture`() {
        val base=save(report.get("cases")[0].getString("before"))
        assertNull(model.begin(base,99));assertNull(model.begin(base.withVariables(mapOf(0 to 3)),1))
        val shop=base.travel(5,0,0,152,176,0,0,mapOf(0 to 4))
        assertNull(model.begin(shop,1));assertNull(model.begin(shop,2))
    }
}
