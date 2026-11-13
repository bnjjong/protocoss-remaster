package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FieldApproachTest {
    private fun json(path:String)=JsonReader().parse(Files.readString(Path.of("../$path")))
    @Test fun `NPC working screen boundaries match the native drawing loop`() {
        for(row in json("docs/restoration/animation-approach-native.json").get("visibility")) {
            assertEquals(row[2].asBoolean(),FieldApproach.npcVisible(row[0].asInt()+112,row[1].asInt()+56,112,56))
        }
    }
    @Test fun `each approach tick matches native movement camera pose collision and NPC state`() {
        val report=json("docs/restoration/animation-approach-native.json")
        val opening=json("assets/opening/opening.json")
        val rules=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) { Files.readAllBytes(Path.of("../assets/data/field/$it")) }
        for((index,row) in report.get("cases").withIndex()) {
            val start=row.get("start").asIntArray();val scroll=row.get("scroll").asIntArray();val target=row.get("target").asIntArray()
            val map=row.getInt("map");val data=opening.get("maps").get(map.toString())
            val npcs=FieldNpcs(data)
            val walk=FieldApproach(start[0],start[1],start[2],start[3],scroll[0],scroll[1],target[0],target[1],target[2],target[3],(data.getInt("width")-40)*8,(data.getInt("height")-25)*8,row.getInt("step"),
                { x,y,d,l -> if(npcs.blocksPlayer(x.toFloat(),y.toFloat(),d)) FieldRules.Step(0,l) else rules.step(map,x.toFloat(),y.toFloat(),d,l) },
                { x,y -> npcs.tick(x.toFloat(),y.toFloat()) })
            for((tick,frame) in row.get("frames").withIndex()) {
                walk.update(1f/70f)
                val label="표본 $index, 틱 $tick"
                assertEquals(frame.get("hero").asIntArray().toList(),listOf(walk.x,walk.y,walk.pose,walk.layer,walk.direction),label)
                assertEquals(frame.get("scroll").asIntArray().toList(),listOf(walk.scrollX,walk.scrollY),label)
                assertEquals(frame.get("npcs").map { it.asIntArray().toList() },npcs.actors.map { listOf(it.x,it.y,it.tileX,it.tileY,it.direction,it.frame,it.mode) },label)
            }
            assertEquals(row.getBoolean("ended"),walk.finished,"표본 $index")
            val ticks=walk.ticks;walk.update(0f);assertEquals(ticks,walk.ticks)
            if(walk.finished) { walk.update(1f);assertEquals(ticks,walk.ticks) }
        }
    }
}
