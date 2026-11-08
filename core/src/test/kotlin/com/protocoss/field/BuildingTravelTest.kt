package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class BuildingTravelTest {
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/buildings-native-verification.json")))
    private val travel=BuildingTravel(Files.readString(Path.of("../assets/data/field/buildings.json")))
    private fun start()=DosSave.afterOpening(Files.readAllBytes(Path.of("../assets/data/original/start.dat")))
    private fun rules()=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) { Files.readAllBytes(Path.of("../assets/data/field/$it")) }
    private fun sourceMap(group:Int,stage:Int)=when(group) { 3->if(stage<3) 4 else 12;4->5;5->8;6->9;7->10;8->11;9->6;else->7 }
    @Test fun `native routes preserve every unrelated save byte`() {
        for(case in report.get("cases")) {
            val group=case.getInt("group");val stage=case.getInt("stage");val event=case.getInt("argument")
            val before=start().travel(sourceMap(group,stage),0,0,152,176,4,0,mapOf(0 to group,8 to stage,99 to 123))
            val after=assertNotNull(travel.travel(before,event))
            val expected=case.get("result").get("events").first { it.getString("kind")=="warp" }.get("args").asIntArray().toList()
            assertEquals(expected,listOf(after.map,after.scrollX,after.scrollY,after.screenX,after.screenY,after.pose,after.layer,after.variable(4)))
            assertEquals(case.get("result").get("variables").getInt("0"),after.variable(0))
            assertEquals(event,after.variable(1));assertEquals(stage,after.variable(8));assertEquals(123,after.variable(99))
            val a=before.bytes();val b=after.bytes()
            for(i in a.indices) if(i !in 96..118 && i !in 2342..2345 && i !in 2350..2351) assertEquals(a[i],b[i],"보존할 저장 바이트 $i")
            assertEquals(case.get("result").get("events").any { it.getString("kind")=="effect" && it.getInt("id")==0 },travel.hasDoorSound(before,event))
        }
    }
    @Suppress("UNCHECKED_CAST") private fun movement(rules:FieldRules,map:Int):ByteArray {
        val maps=FieldRules::class.java.getDeclaredField("maps").apply { isAccessible=true }.get(rules) as Map<String,Any>
        val data=maps.getValue(map.toString())
        return data.javaClass.getDeclaredField("movement").apply { isAccessible=true }.get(data) as ByteArray
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun `each native door changes only its own cells and collision`() {
        val rules=rules()
        for(case in report.get("doors")) {
            val map=case.getInt("map");rules.reset(map)
            val x=case.getFloat("x");val y=case.getFloat("y")
            val event=rules.event(map,x,y,1);assertTrue(event in listOf(0,9))
            assertEquals(0,rules.step(map,x,y,1,0).direction)
            val changes=assertNotNull(rules.openDoor(map,x,y,1))
            assertEquals(case.get("changes").toString(),changes.toString())
            assertEquals(case.getString("sha256"),hash(movement(rules,map)))
            assertEquals(null,rules.event(map,x,y,1));assertEquals(null,rules.openDoor(map,x,y,1))
            assertEquals(1,rules.step(map,x,y,1,0).direction)
            rules.reset(map)
            assertEquals(event,rules.event(map,x,y,1));assertEquals(0,rules.step(map,x,y,1,0).direction)
        }
        for(case in report.get("combined")) {
            val map=case.getInt("map");rules.reset(map)
            for(door in report.get("doors").filter { it.getInt("map")==map }.reversed()) rules.openDoor(map,door.getFloat("x"),door.getFloat("y"),1)
            assertEquals(case.getString("sha256"),hash(movement(rules,map)))
        }
    }
    @Test fun `wrong direction unknown exit and unrelated group do not warp`() {
        val save=start().travel(4,0,0,152,176,0,0,mapOf(0 to 3,8 to 0))
        assertNull(travel.travel(save,21));assertNull(travel.travel(save,0))
        assertNull(travel.travel(save.travel(5,0,0,152,176,0,0,mapOf(0 to 3)),2))
        val rules=rules();assertNull(rules.openDoor(4,304f,144f,4))
        assertNotNull(rules.event(4,304f,144f,1))
    }
    @Test fun `new indoor npc records match native movement`() {
        val maps=JsonReader().parse(Files.readString(Path.of("../assets/opening/opening.json"))).get("maps")
        for(case in report.get("motions")) {
            val npcs=FieldNpcs(maps.get(case.getInt("map").toString()));val hero=case.get("hero")
            val digest=MessageDigest.getInstance("SHA-256")
            repeat(case.getInt("ticks")) {
                npcs.tick(hero[0].asFloat(),hero[1].asFloat())
                val bytes=java.nio.ByteBuffer.allocate(npcs.actors.size*28).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                npcs.actors.forEach { a -> listOf(a.x,a.y,a.tileX,a.tileY,a.direction,a.frame,a.mode).forEach { bytes.putInt(it) } }
                digest.update(bytes.array())
            }
            assertEquals(case.getString("sha256"),digest.digest().joinToString("") { "%02x".format(it) },"실내 ${case.getInt("map")}")
        }
    }
    @Test fun `native walking and action flags keep inactive side entrance closed`() {
        val rules=rules()
        for(case in report.get("front_events")) {
            val map=case[0].asInt();val x=case[1].asFloat();val y=case[2].asFloat();val direction=case[3].asInt()
            val walk=case[4].takeUnless { it.isNull }?.asInt()
            val action=case[5].takeUnless { it.isNull }?.asInt()
            val pose=when(direction) { 1->0;2->4;4->8;else->12 }
            assertEquals(walk,rules.event(map,x,y,direction),case.toString())
            assertEquals(action,rules.interaction(map,x,y,pose),case.toString())
        }
        assertNull(rules.event(4,400f,112f,8));assertNull(rules.interaction(4,400f,112f,12))
    }
}
