package com.protocoss.field

import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TownTravelTest {
    private fun yard(seen:Int=0,stage:Int=0)=DosSave.afterOpening(Files.readAllBytes(Path.of("../assets/data/original/start.dat")))
        .travel(0,24,16,160,104,8,1,mapOf(0 to 1,7 to seen,8 to stage))
    @Test fun `first exit enters forest before town`() {
        val forest=assertNotNull(TownTravel.travel(yard(),2))
        assertEquals(3,forest.map);assertEquals(120,forest.screenX);assertEquals(16,forest.screenY)
        assertEquals(2,forest.variable(0));assertEquals(0,forest.variable(7))
        val town=assertNotNull(TownTravel.travel(forest,1))
        assertEquals(4,town.map);assertEquals(88,town.screenX);assertEquals(24,town.screenY)
        assertEquals(3,town.variable(0));assertEquals(24,town.variable(4));assertEquals(1,town.variable(1))
    }
    @Test fun `reentry skips encounter and preserves correct town stage`() {
        for(stage in listOf(0,2,3,5)) {
            val source=yard(1,stage)
            val town=assertNotNull(TownTravel.travel(source,2))
            assertEquals(if(stage<3) 4 else 12,town.map)
            assertEquals(1,town.variable(7));assertEquals(stage,town.variable(8))
            val back=assertNotNull(TownTravel.travel(town,1))
            assertEquals(0,back.map);assertEquals(40,back.scrollX);assertEquals(25,back.scrollY)
            assertEquals(304,back.screenX);assertEquals(184,back.screenY);assertEquals(12,back.pose)
            assertEquals(1,back.variable(0));assertEquals(25,back.variable(4))
        }
    }
    @Test fun `unknown exit does not replace existing field event`() {
        assertNull(TownTravel.travel(yard(),1))
        assertNull(TownTravel.travel(yard(),3))
    }
    @Test fun `every native warp fixture preserves map coordinates and variables`() {
        val data=com.badlogic.gdx.utils.JsonReader().parse(Files.readString(Path.of("../docs/town-native-verification.json")))
        var checked=0
        for(case in data.get("cases")) {
            val group=case.getInt("group");val stage=case.getInt("stage");val seen=case.getInt("seen")
            val event=case.getInt("argument")
            val warp=case.get("result").get("events").firstOrNull { it.getString("kind")=="warp" } ?: continue
            val source=yard(seen,stage).travel(when(group) { 1->0;2->3;else->if(stage<3) 4 else 12 },0,0,120,16,8,0,mapOf(0 to group))
            val destination=assertNotNull(TownTravel.travel(source,event))
            val args=warp.get("args")
            assertEquals(listOf(args[0].asInt(),args[1].asInt(),args[2].asInt(),args[3].asInt(),args[4].asInt(),args[5].asInt(),args[6].asInt(),args[7].asInt()),
                listOf(destination.map,destination.scrollX,destination.scrollY,destination.screenX,destination.screenY,destination.pose,destination.layer,destination.variable(4)))
            for(index in listOf(0,1,7,8)) assertEquals(case.get("result").get("variables").getInt(index.toString()),destination.variable(index))
            checked++
        }
        assertEquals(24,checked)
    }
}
