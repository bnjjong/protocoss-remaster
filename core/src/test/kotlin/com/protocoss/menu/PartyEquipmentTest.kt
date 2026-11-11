package com.protocoss.menu

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class PartyEquipmentTest {
    private val model=Equipment(JsonReader().parse(Files.readString(Path.of("../assets/ui/equipment/equipment.json"))))
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/party-equipment-native.json")))
    private fun decode(text:String)=Base64.getDecoder().decode(text)
    private fun patched(before:ByteArray,patch:JsonValue)=before.copyOf().also { b -> patch.forEach { b[it[0].asInt()]=it[1].asInt().toByte() } }
    @Test fun `all eleven character slot lists and full native states match`() {
        for(row in report.get("matrices")) {
            val index=row.getInt("character")*6+row.getInt("slot")
            val save=DosSave.parse(decode(report.get("fixtures")[index].asString()))
            assertEquals(row.get("ids").map { it.asInt() },model.candidates(save,row.getInt("character"),row.getInt("slot")).map { it.id })
        }
        for(row in report.get("cases")) {
            val before=decode(report.get("fixtures")[row.getInt("fixture")].asString())
            val character=row.getInt("character");val slot=row.getInt("slot");val id=row.getInt("item")
            val detached=model.beginSlot(DosSave.parse(before),character,slot)
            assertContentEquals(patched(before,row.get("detached")),detached.bytes(),"해제 $character/$slot/$id")
            assertTrue(model.canEquip(detached,id,character,slot))
            val preview=model.preview(detached,id,character,slot)
            assertContentEquals(row.get("preview").asIntArray(),intArrayOf(0,1,2,3,5,4).map { preview[it] }.toIntArray(),"미리보기 $character/$slot/$id")
            val after=model.equip(detached,id,character,slot)
            assertContentEquals(patched(detached.bytes(),row.get("after")),after.bytes(),"장착 $character/$slot/$id")
            assertEquals(if(id==157)2 else row.getInt("step_before"),row.getInt("step"))
        }
    }
    @Test fun `native replacement forms count overflow and asymmetric gem restrictions match`() {
        for(row in report.get("transitions")) {
            val before=decode(row.getString("before"))
            val after=model.beginSlot(DosSave.parse(before),row.getInt("character"),row.getInt("slot"))
            assertContentEquals(patched(before,row.get("after")),after.bytes())
        }
        for(row in report.get("restrictions")) {
            val before=decode(row.getString("before"));val save=DosSave.parse(before)
            val id=row.getInt("item");val character=row.getInt("character");val slot=row.getInt("slot")
            assertEquals(!row.getBoolean("rejected"),model.canEquip(save,id,character,slot),"제한 $character/$slot/$id")
            if(row.getBoolean("rejected")) assertFailsWith<IllegalArgumentException> { model.equip(save,id,character,slot) }
            else assertContentEquals(patched(before,row.get("after")),model.equip(save,id,character,slot).bytes())
        }
    }
    @Test fun `inactive members cannot change equipment and active order skips gaps`() {
        val raw=decode(report.get("fixtures")[0].asString())
        for(i in 0..10) raw[347+153*i]=if(i==2 || i==7)1 else 0
        val save=DosSave.parse(raw);assertEquals(listOf(2,7),model.party(save))
        assertTrue(model.candidates(save,0,2).isEmpty())
        assertFailsWith<IllegalArgumentException> { model.beginSlot(save,0,2) }
        val menu=EquipmentMenu(initialCharacter=2)
        menu.navigate("up",model,save);assertEquals(7,menu.character)
        menu.navigate("down",model,save);assertEquals(2,menu.character)
    }
    @Test fun `native slot navigation and cloak step changes follow menu actions`() {
        val raw=decode(report.get("fixtures")[0].asString());val save=DosSave.parse(raw)
        for(d in listOf("up","down","left","right")) for(slot in 0..5) {
            val menu=EquipmentMenu(slot);menu.confirm(model,save);menu.navigate(d,model,save)
            assertEquals(model.data.get("slot_navigation").get(d)[slot].asInt(),menu.slot)
        }
        val menu=EquipmentMenu(4);var current=menu.confirm(model,save)
        current=menu.confirm(model,current)
        val candidates=model.candidates(current,0,4);menu.move(candidates.indexOfFirst { it.id==157 },candidates.size)
        current=menu.confirm(model,current);assertEquals(2,menu.stepChange)
        current=menu.confirm(model,current);assertEquals(1,menu.stepChange)
        assertEquals(255,model.equipped(current,0,4))
    }
}
