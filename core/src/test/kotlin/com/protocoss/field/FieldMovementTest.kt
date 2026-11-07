package com.protocoss.field

import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FieldMovementTest {
    private fun rules()=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) {
        Files.readAllBytes(Path.of("../assets/data/field/$it"))
    }
    private fun start()=DosSave.afterOpening(Files.readAllBytes(Path.of("../assets/data/original/start.dat")))
    @Test fun `native bed furniture and door collision fixtures`() {
        val rules=rules()
        for((x,y,allowed) in listOf(Triple(80f,40f,listOf(0,2,0,8)),Triple(104f,80f,listOf(0,2,0,8)),
            Triple(144f,168f,listOf(1,0,4,0)),Triple(176f,80f,listOf(1,0,0,8)))) {
            assertEquals(allowed,listOf(1,2,4,8).map { rules.step(2,x,y,it,0).direction })
        }
        assertEquals(1,rules.event(2,144f,168f,4))
        assertEquals(1,rules.event(0,336f,192f,1))
        assertEquals(null,rules.event(2,128f,168f,4))
    }
    @Test fun `native stairs change height layer and opened door removes blocking`() {
        val rules=rules()
        assertEquals(FieldRules.Step(1,1),rules.step(0,416f,264f,1,0))
        assertEquals(FieldRules.Step(4,0),rules.step(0,416f,256f,4,1))
        assertEquals(FieldRules.Step(0,1),rules.step(0,336f,216f,1,1))
        assertEquals(FieldRules.Step(1,1),rules.step(3,336f,216f,1,1))
    }
    @Test fun `stage two reentry keeps party removal and item returns`() {
        val bytes=start().bytes()
        bytes[500]=1;bytes[540]=1;bytes[630]=163.toByte();bytes[631]=174.toByte();bytes[325]=9;bytes[336]=20
        val outside=DosSave.parse(bytes).travel(0,24,16,160,104,0,1,mapOf(0 to 1,8 to 2))
        val inside=assertNotNull(CabinDoors.travel(outside,1))
        assertEquals(1,inside.map);assertEquals(3,inside.variable(8));assertEquals(7,inside.variable(1))
        val result=inside.bytes()
        assertEquals(0,result[500].toInt());assertEquals(0,result[540].toInt())
        assertEquals(255,result[630].toInt() and 255);assertEquals(255,result[631].toInt() and 255)
        assertEquals(10,result[325].toInt());assertEquals(21,result[336].toInt())
    }
    @Test fun `wall corner dash and long frames cannot tunnel`() {
        val mover=FieldMovement(rules(),2,0);mover.place(80f,40f)
        mover.update(3f,1,true) { false };assertEquals(40f,mover.y)
        mover.update(3f,3,true) { false };assertEquals(80f,mover.x);assertEquals(40f,mover.y)
        mover.update(1f/70f,2,false) { false };assertEquals(81f,mover.x)
        mover.update(7f/70f,0,false) { false };assertEquals(88f,mover.x)
        assertEquals(40f,mover.y)
    }
    @Test fun `door runs before movement and preserves unrelated save bytes`() {
        val initial=start();val outside=assertNotNull(CabinDoors.travel(initial,1))
        assertEquals(0,outside.map);assertEquals(336f,outside.worldX);assertEquals(216f,400f-outside.worldY(400f))
        assertEquals(1,outside.layer);assertEquals(1,outside.variable(0))
        val inside=assertNotNull(CabinDoors.travel(outside,1))
        assertEquals(2,inside.map);assertEquals(144f,inside.worldX);assertEquals(168f,200f-inside.worldY(200f))
        assertEquals(0,inside.variable(0));assertEquals(1,inside.variable(1))
        val bytes=initial.bytes();val actual=inside.bytes()
        for(i in bytes.indices) if(i !in 96..118 && i !in 2342..2345 && i !in 2350..2351) assertEquals(bytes[i],actual[i],"바뀌면 안 되는 저장 바이트 $i")
        val mover=FieldMovement(rules(),2,0);mover.place(144f,168f)
        var fired=0
        assertTrue(mover.update(1f,4,true) { fired++;true })
        assertEquals(1,fired);assertEquals(168f,mover.y)
    }
    @Test fun `daytime reentry selects correct map and does not reset progress`() {
        val progressed=start().travel(0,24,16,160,104,8,1,mapOf(0 to 1,8 to 3,6 to 1,99 to 123))
        val inside=assertNotNull(CabinDoors.travel(progressed,1))
        assertEquals(1,inside.map);assertEquals(3,inside.variable(8));assertEquals(1,inside.variable(6));assertEquals(123,inside.variable(99))
        assertEquals(0,inside.pose);assertEquals(0,inside.layer)
    }
}
