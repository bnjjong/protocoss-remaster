package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.field.*
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class CabinStoryTest {
    private fun json(path:String)=JsonReader().parse(Files.readString(Path.of("../$path")))
    private val opening=json("assets/opening/opening.json")
    private val report=json("docs/restoration/cabin-story-native.json")
    private val story=FieldStory(json("assets/data/events/story.json"),opening)
    private val archive=EventArchive(Files.readAllBytes(Path.of("../assets/data/events/area00.gm")))
    private val rules=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) { Files.readAllBytes(Path.of("../assets/data/field/$it")) }
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    @Test fun `wake and fire approach nested dialogue and complete saves match original functions`() {
        val characters=(opening.get("font").getString("characters")+json("assets/data/field/indoor-dialogues.json").get("font").getString("characters")).toSet()
        for(row in report.get("cases")) {
            val before=bytes(row.getString("before"));val source=DosSave.parse(before)
            val script=assertNotNull(FieldScript.prepare(archive,source,row.getInt("event"),opening.get("maps"),characters,true,story=story))
            for(request in row.get("requests")) {
                val stop=assertIs<EventMachine.Stop.Animation>(script.next());assertSame(stop,script.next())
                assertEquals(listOf(request.getInt("id"),request.getInt("mode"),request.getInt("music")),listOf(stop.id,stop.mode,stop.music))
                val proof=row.get("approach");val start=proof.get("start").asIntArray();val target=proof.get("target").asIntArray();val map=source.map
                val npcs=FieldNpcs(opening.get("maps").get(map.toString()))
                val walk=FieldApproach(start[0],start[1],start[2],start[3],0,0,target[0],target[1],0,0,0,0,1,
                    { x,y,d,l -> rules.step(map,x.toFloat(),y.toFloat(),d,l) },
                    { x,y -> npcs.tick(x.toFloat(),y.toFloat()) })
                for(frame in proof.get("frames")) {
                    walk.update(1f/70f)
                    assertEquals(frame.get("hero").asIntArray().toList(),listOf(walk.x,walk.y,walk.pose,walk.layer,walk.direction))
                    assertEquals(frame.get("scroll").asIntArray().toList(),listOf(walk.scrollX,walk.scrollY))
                    assertEquals(frame.get("npcs").map { it.asIntArray().toList() },npcs.actors.map { listOf(it.x,it.y,it.tileX,it.tileY,it.direction,it.frame,it.mode) })
                }
                assertTrue(walk.finished)
                script.adopt(script.save.travel(map,0,0,walk.x+16,walk.y+16,walk.pose,walk.layer,emptyMap()))
                val plan=script.beginAnimation(stop,story)
                assertEquals(plan.events.values.toList(),request.get("calls").map { it.getInt("argument") })
                for(call in request.get("calls")) {
                    val child=script.child(call.getInt("argument"))
                    for(expected in call.get("requests")) {
                        val talk=assertIs<EventMachine.Stop.Dialogue>(child.next())
                        assertContentEquals(bytes(expected.getString("raw")),talk.bytes());child.resumeDialogue()
                    }
                    assertIs<EventMachine.Stop.Finished>(child.next())
                    assertContentEquals(bytes(call.getString("after")),child.save.bytes())
                    script.adopt(child.save)
                }
                script.finishAnimation(stop,story,plan)
                assertContentEquals(bytes(request.getString("after")),script.save.bytes())
            }
            assertIs<EventMachine.Stop.Finished>(script.next())
            assertContentEquals(bytes(row.getString("after")),script.save.bytes(),row.getString("name"))
            assertContentEquals(before,source.bytes())
        }
    }
    @Test fun `waking preserves the current map and sets its flag only after the animation`() {
        val row=report.get("cases").first { it.getInt("map")==2 && it.getInt("event")==16 && it.getInt("state")==0 }
        val source=DosSave.parse(bytes(row.getString("before")));val script=FieldScript(archive,source,16)
        val request=assertIs<EventMachine.Stop.Animation>(script.next());val plan=script.beginAnimation(request,story)
        assertEquals(2,script.save.map);assertEquals(0,script.save.variable(6))
        val timeline=com.protocoss.opening.OpeningTimeline(opening,plan.program,plan.program,script.save.map,plan.events)
        assertTrue(timeline.continuesField);assertEquals(0f,timeline.fadeAlpha);assertEquals(2,timeline.map)
        script.finishAnimation(request,story,plan)
        assertEquals(0,script.save.variable(6));assertIs<EventMachine.Stop.Finished>(script.next())
        assertEquals(1,script.save.variable(6));assertEquals(2,script.save.map)
    }
}
