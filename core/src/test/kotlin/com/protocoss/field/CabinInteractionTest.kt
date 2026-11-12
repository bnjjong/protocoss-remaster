package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.opening.OpeningTimeline
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class CabinInteractionTest {
    private val root=Path.of("..")
    private val data=JsonReader().parse(Files.readString(root.resolve("assets/opening/opening.json")))
    private val report=JsonReader().parse(Files.readString(root.resolve("docs/cabin-interaction-native.json")))
    private val model=CabinInteraction(data)
    private val rules=FieldRules(Files.readString(root.resolve("assets/data/field/field.json"))) { Files.readAllBytes(root.resolve("assets/data/field/$it")) }
    private fun save(s:String)=DosSave.parse(Base64.getDecoder().decode(s))
    @Test fun `all original front positions directions and unaligned coordinates match`() {
        for(row in report.get("front_events")) {
            val pose=mapOf(1 to 0,2 to 4,4 to 8,8 to 12).getValue(row[3].asInt())
            val expected=if(row[4].isNull) null else row[4].asInt()
            assertEquals(expected,rules.interaction(row[0].asInt(),row[1].asFloat(),row[2].asFloat(),pose),row.toString())
        }
    }
    @Test fun `original object dialogue and complete saved records match across story states`() {
        for(row in report.get("cases")) {
            val event=row.getInt("event");val before=save(row.getString("before"))
            val pages=model.pages(before,event)
            val nativeText=row.get("events").filter { it.getString("kind")=="dialogue" }
            if(nativeText.isEmpty()) { assertNull(pages);continue }
            val text=assertNotNull(pages).map { it.getString("text") }.joinToString(" ").replace("\n","")
            for(e in nativeText) for(chunk in e.getString("text").split(Regex("/\\[[^]]+]|/[NDE]"))) assertTrue(text.contains(chunk),chunk)
            assertContentEquals(save(row.getString("after")).bytes(),before.calledEvent(event).bytes())
        }
    }
    @Test fun `fire preserves current cabin and reaches original final actor checkpoint without fading`() {
        for(map in (1..2)) {
            val row=report.get("cases").first { it.getInt("map")==map && it.getInt("event")==15 }
            val before=save(row.getString("before"));val expected=report.get("fire_checkpoints").last().get("actors")[0]
            val destination=assertNotNull(model.fireDestination(before,15))
            assertEquals(map,destination.map);assertEquals(before.variable(6),destination.variable(6));assertEquals(before.variable(8),destination.variable(8))
            assertEquals(expected.getInt("x")+16,destination.screenX);assertEquals(expected.getInt("y")+16,destination.screenY)
            assertEquals(expected.getInt("frame"),destination.pose);assertEquals(8,destination.variable(1))
            val timeline=OpeningTimeline(data,6,6,map);var talked=false
            repeat(1000) {
                assertEquals(map,timeline.map);assertEquals(0f,timeline.fadeAlpha)
                if(timeline.page!=null) {
                    assertEquals(8,timeline.dialogueId);assertEquals(expected.getInt("x").toFloat(),timeline.actors[0].x)
                    assertEquals(expected.getInt("y").toFloat(),timeline.actors[0].y);talked=true;timeline.advance()
                }
                timeline.update(1f/70f)
            }
            assertTrue(timeline.finished);assertTrue(talked)
        }
    }
    @Test fun `other maps groups and unsupported events do not become cabin objects`() {
        val before=save(report.get("cases")[0].getString("before"))
        assertNull(model.pages(before,99));assertNull(model.fireDestination(before,10))
        assertNull(model.pages(before.withVariables(mapOf(0 to 7)),10))
        assertNull(model.fireDestination(before.travel(10,0,0,100,100,8,0,mapOf(0 to 7)),15))
    }
}
