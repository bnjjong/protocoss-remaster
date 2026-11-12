package com.protocoss.menu

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class ShopTest {
    private val model=Shop(JsonReader().parse(Files.readString(Path.of("../assets/ui/shop/shop.json"))))
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/shop-native.json")))
    private fun decode(s:String)=Base64.getDecoder().decode(s)
    @Test fun `all native purchases sales cancellation and effects match full save records`() {
        for(row in report.get("cases")) {
            var save=DosSave.parse(decode(row.getString("before")));val menu=ShopMenu()
            if(row.getBoolean("sell")) menu.navigate("up",model,save)
            menu.confirm(model,save)
            while(menu.item(model,save)!=row.getInt("item")) menu.navigate("right",model,save)
            menu.confirm(model,save);val effects=mutableListOf<Int>()
            for(key in row.get("keys")) {
                when(key.asInt()) {
                    1->menu.back()
                    28,57->save=menu.confirm(model,save,key.asInt()==57)
                    else->menu.navigate(mapOf(72 to "up",80 to "down",75 to "left",77 to "right").getValue(key.asInt()),model,save)
                }
                menu.effect?.let { effects+=it }
                repeat(12) {
                    if(menu.stage==ShopMenu.Stage.TRADE) { save=menu.update(1f/70f,model,save);menu.effect?.let { effects+=it } }
                }
            }
            assertContentEquals(decode(row.getString("after")),save.bytes(),row.getString("name"))
            assertEquals(row.get("effects").map { it.asInt() },effects,row.getString("name"))
        }
    }
    @Test fun `native catalogs include zero price sales and omit unavailable purchases`() {
        for(row in report.get("lists")) {
            val save=DosSave.parse(decode(row.getString("before")))
            for(sell in listOf(false,true)) {
                val expected=row.get(if(sell) "sell" else "buy")
                assertEquals(expected.map { it[0].asInt() },model.candidates(save,sell))
                assertEquals(expected.map { it[1].asInt() },model.candidates(save,sell).map { model.price(it,sell) })
            }
        }
    }
    @Test fun `native wheel up down space enter and escape match`() {
        val save=DosSave.parse(decode(report.get("lists")[1].getString("before")))
        for(row in report.get("homes")) {
            val menu=ShopMenu();val effects=mutableListOf<Int>()
            for(key in row.get("keys")) {
                when(key.asInt()) { 72->menu.navigate("up",model,save);80->menu.navigate("down",model,save);1->menu.back();else->menu.confirm(model,save,key.asInt()==57) }
                menu.effect?.let { effects+=it }
            }
            assertEquals(row.getInt("choice"),menu.choice)
            assertEquals(row.get("effects").map { it.asInt() },effects)
            assertEquals(row.getLong("result")==4294967295L || menu.choice==6,menu.closed)
        }
    }
}
