package com.protocoss.menu

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class AutomaticEquipmentTest {
    private val source=Files.readString(Path.of("../assets/ui/equipment/equipment.json"))
    private val model=Equipment(JsonReader().parse(source))
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/automatic-equipment-native.json")))
    private fun decode(text:String)=Base64.getDecoder().decode(text)
    private fun patched(before:ByteArray,patch:JsonValue)=before.copyOf().also { b -> patch.forEach { b[it[0].asInt()]=it[1].asInt().toByte() } }
    @Test fun `all native automatic sequences preserve exact saves and speed`() {
        for(row in report.get("cases")) {
            val data=JsonReader().parse(source)
            for(override in row.get("overrides")) {
                val item=data.get("weapons")[override[0].asInt()]
                item.remove("stats");item.addChild("stats",JsonValue(JsonValue.ValueType.array).apply {
                    override[1].forEach { addChild(JsonValue(it.asInt().toLong())) }
                })
            }
            val currentModel=Equipment(data);val character=row.getInt("character")
            var expected=decode(row.getString("before"));var save=DosSave.parse(expected)
            for(step in row.get("steps")) {
                expected=patched(expected,step.get("after"))
                save=if(step.getString("action")=="equip") currentModel.autoEquip(save,character) else currentModel.autoUnequip(save,character)
                assertContentEquals(expected,save.bytes(),"${row.getString("name")}/${step.getString("action")}")
                assertEquals(save.variable(297),step.getInt("step"))
            }
        }
    }
    @Test fun `native home key sequences clamp tabs cycle members and stay home after automatic actions`() {
        for(row in report.get("controllers")) {
            val before=decode(row.getString("before"));var save=DosSave.parse(before);val menu=EquipmentMenu()
            val tabs=mutableListOf<Int>()
            for(key in row.get("keys")) {
                when(key.asInt()) {
                    72 -> menu.navigate("up",model,save)
                    80 -> menu.navigate("down",model,save)
                    75 -> menu.navigate("left",model,save)
                    77 -> menu.navigate("right",model,save)
                    28,57 -> save=menu.confirm(model,save)
                    1 -> assertTrue(menu.back())
                }
                tabs+=menu.tab
                assertEquals(EquipmentMenu.Stage.HOME,menu.stage)
                assertNull(menu.stepChange)
            }
            assertEquals(row.get("tabs").map { it.asInt() },tabs,row.getString("name"))
            assertContentEquals(patched(before,row.get("after")),save.bytes(),row.getString("name"))
        }
    }
    @Test fun `automatic actions reject inactive members and leave manual selection available`() {
        val before=decode(report.get("cases")[0].getString("before"));before[500]=0
        val save=DosSave.parse(before)
        assertFailsWith<IllegalArgumentException> { model.autoEquip(save,1) }
        assertFailsWith<IllegalArgumentException> { model.autoUnequip(save,1) }
        val menu=EquipmentMenu();menu.navigate("right",model,save);menu.navigate("left",model,save)
        assertContentEquals(before,menu.confirm(model,save).bytes());assertEquals(EquipmentMenu.Stage.SLOT,menu.stage)
        assertFalse(menu.back());assertEquals(0,menu.tab);assertTrue(menu.back())
    }
}
