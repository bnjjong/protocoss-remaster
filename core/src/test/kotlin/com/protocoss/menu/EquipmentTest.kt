package com.protocoss.menu

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class EquipmentTest {
    private val model=Equipment(JsonReader().parse(Files.readString(Path.of("../assets/ui/equipment/equipment.json"))))
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/equipment-native-verification.json")))
    private fun save(text:String)=DosSave.parse(Base64.getDecoder().decode(text))
    @Test fun `native removal candidates preview and equipped full saves`() {
        for(case in report.get("cases")) {
            val before=save(case.getString("before"));val item=case.getInt("item")
            val removed=model.beginWeapon(before)
            assertContentEquals(save(case.getString("detached")).bytes(),removed.bytes(),"해제 $item/${case.getInt("variant")}")
            assertEquals(case.get("candidates").map { it.asInt() },model.candidates(removed).map { it.id })
            val predicted=model.preview(removed,item)
            assertContentEquals(case.get("preview").asIntArray(),intArrayOf(0,1,2,3,5,4).map { predicted[it] }.toIntArray(),"미리보기 $item/${case.getInt("variant")}")
            assertContentEquals(save(case.getString("after")).bytes(),model.equip(removed,item).bytes(),"장착 $item/${case.getInt("variant")}")
        }
    }
    @Test fun `no item invalid category and two handed shield cannot equip`() {
        val empty=save(report.get("cases")[0].getString("before")).bytes().also { it[475]=(-1).toByte();it.fill(0,162,337) }
        val before=DosSave.parse(empty)
        assertFailsWith<IllegalArgumentException> { model.equip(before,0) }
        val invalid=empty.copyOf().also { it[162+120]=1 }
        assertFailsWith<IllegalArgumentException> { model.equip(DosSave.parse(invalid),120) }
        val two=model.weapons.first { it.allowed && it.category==5 }
        val blocked=empty.copyOf().also { it[162+two.id]=1;it[476]=90 }
        assertFalse(model.canEquip(DosSave.parse(blocked),two.id))
        assertFailsWith<IllegalArgumentException> { model.equip(DosSave.parse(blocked),two.id) }
        assertContentEquals(empty,before.bytes())
        for(case in report.get("rejections")) {
            val native=save(case.getString("before"));val id=case.getInt("item")
            assertFalse(model.canEquip(native,id))
            assertContentEquals(native.bytes(),save(case.getString("after")).bytes())
            assertTrue(case.get("events").any { it.getString("kind")=="effect" && it.getInt("id")==13 })
        }
    }
    @Test fun `native candidate filter includes only allowed owned weapons`() {
        val bytes=save(report.get("cases")[0].getString("before")).bytes().also { it.fill(1,162,337) }
        assertEquals(model.data.get("eligible").map { it.asInt() },model.candidates(DosSave.parse(bytes)).map { it.id })
    }
    @Test fun `native return count preserves byte overflow rather than applying reward cap`() {
        for(case in report.get("detached_cases"))
            assertContentEquals(save(case.getString("after")).bytes(),model.beginWeapon(save(case.getString("before"))).bytes())
    }
    @Test fun `selection scrolling cancel and repeated exchange conserve items`() {
        val bytes=save(report.get("cases")[0].getString("before")).bytes().also { it.fill(1,162,337);it[475]=(-1).toByte() }
        var current=DosSave.parse(bytes);val menu=EquipmentMenu(2)
        current=menu.confirm(model,current);assertEquals(EquipmentMenu.Stage.SLOT,menu.stage)
        current=menu.confirm(model,current);assertEquals(EquipmentMenu.Stage.SELECT,menu.stage)
        menu.move(20,model.candidates(current).size);assertEquals(20,menu.selected);assertEquals(17,menu.scroll)
        menu.move(-100,model.candidates(current).size);assertEquals(0,menu.selected);assertEquals(0,menu.scroll)
        current=menu.confirm(model,current);assertEquals(0,model.equipped(current));assertEquals(0,model.quantity(current,0,true))
        repeat(3) { current=menu.confirm(model,current);current=menu.confirm(model,current) }
        assertEquals(0,model.equipped(current));assertEquals(0,model.quantity(current,0,true))
        current=menu.confirm(model,current);assertFalse(menu.back())
        assertEquals(255,model.equipped(current));assertEquals(1,model.quantity(current,0,true))
    }
}
