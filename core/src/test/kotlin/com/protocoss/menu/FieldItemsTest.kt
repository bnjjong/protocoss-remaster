package com.protocoss.menu

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class FieldItemsTest {
    private val equipment=Equipment(JsonReader().parse(Files.readString(Path.of("../assets/ui/equipment/equipment.json"))))
    private val model=FieldItems(equipment)
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/field-items-native.json")))
    private fun decode(value:String)=Base64.getDecoder().decode(value)
    @Test fun `all native target effects counts and unchanged records match exactly`() {
        for(row in report.get("cases")) {
            val raw=decode(report.get("fixtures")[row.getInt("fixture")].asString());val expected=raw.copyOf()
            row.get("after").forEach { expected[it[0].asInt()]=it[1].asInt().toByte() }
            var save=DosSave.parse(raw)
            val effects=mutableListOf<Int>()
            repeat(2) {
                val result=model.use(save,row.getInt("item"),row.getInt("character"))
                effects+=if(result.blocked)13 else 8;save=result.save
            }
            assertContentEquals(expected,save.bytes(),"${row.getInt("fixture")}/${row.getInt("item")}")
            // 원작 대상 선택은 취소할 때도 확인 효과음 8을 낸다. 방향키 효과음은 비교에서 뺀다.
            assertEquals(row.get("effects").map { it.asInt() }.filter { it!=12 },effects+8)
            assertEquals(save.variable(297),row.getInt("step"))
        }
    }
    @Test fun `all native target grid transitions match for one through five members`() {
        for(row in report.get("navigation")) {
            val menu=ItemTargetMenu(0);val count=row.getInt("count");val index=row.getInt("index")
            repeat(index/3) { menu.navigate("down",count) };repeat(index%3) { menu.navigate("right",count) }
            assertEquals(index,menu.index)
            menu.navigate(row.getString("direction"),count)
            assertEquals(row.getInt("next"),menu.index,"$count/$index/${row.getString("direction")}")
        }
    }
    @Test fun `unavailable quantities inactive members and other item flows do not consume`() {
        val b=decode(report.get("fixtures")[0].asString());b[123]=0;b[500]=0
        val save=DosSave.parse(b)
        assertTrue(model.use(save,0,0).blocked);assertTrue(model.use(save,1,1).blocked)
        val enabled=equipment.items.filter { model.targetable(it.id) }.map { it.id }
        assertEquals(report.get("ids").map { it.asInt() },enabled)
        for(item in equipment.items.filterNot { model.targetable(it.id) }) {
            val result=model.use(save,item.id,0);assertTrue(result.blocked);assertContentEquals(b,result.save.bytes())
        }
    }
}
