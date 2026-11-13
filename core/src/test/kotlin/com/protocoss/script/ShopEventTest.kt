package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.field.*
import com.protocoss.menu.Shop
import com.protocoss.menu.ShopMenu
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class ShopEventTest {
    private fun json(path:String)=JsonReader().parse(Files.readString(Path.of("../$path")))
    private val report=json("docs/restoration/shop-event-native.json")
    private val trades=json("docs/shop-native.json").get("cases").associateBy { it.getString("name") }
    private val opening=json("assets/opening/opening.json")
    private val equipment=json("assets/ui/equipment/equipment.json")
    private val rewardData=json("assets/ui/rewards/rewards.json")
    private val rewards=FieldRewards(equipment,rewardData)
    private val characters=(opening.get("font").getString("characters")+json("assets/data/field/indoor-dialogues.json").get("font").getString("characters")+
        rewardData.get("font").getString("characters")+equipment.get("rects").filter { it.name.startsWith("glyph-") }.map { it.name.substring(6).toInt().toChar() }.joinToString("")).toSet()
    private val archive=EventArchive(Files.readAllBytes(Path.of("../assets/data/events/area00.gm")))
    private val model=Shop(json("assets/ui/shop/shop.json"))
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun program(table:Int,kind:Int):EventArchive {
        val code=byteArrayOf(0xfa.toByte(),3,0,0x10)+ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(table.toShort()).putShort(kind.toShort()).array()+
            byteArrayOf(0xf2.toByte(),99,0,(-56).toByte(),1,0xf1.toByte())
        return EventArchive(ByteBuffer.allocate(8+code.size).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(code.size+2);putShort(1);putShort(0);put(code.map { (it.toInt() xor 127).toByte() }.toByteArray()) }.array())
    }
    private fun snapshot(script:FieldScript,expected:JsonValue) {
        assertEquals(expected.getInt("offset"),script.machine.offset);assertEquals(expected.getLong("instructions"),script.machine.executedInstructions)
        val raw=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { script.machine.variables().forEach { putShort(it.toShort()) } }.array()
        val hash=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        assertEquals(expected.getString("variables"),hash)
    }
    private fun trade(save:DosSave,row:JsonValue):DosSave {
        var result=save;val menu=ShopMenu()
        if(row.getBoolean("sell")) menu.navigate("up",model,result)
        menu.confirm(model,result)
        while(menu.item(model,result)!=row.getInt("item")) menu.navigate("right",model,result)
        menu.confirm(model,result)
        for(key in row.get("keys")) {
            when(key.asInt()) {
                1 -> menu.back()
                28,57 -> result=menu.confirm(model,result,key.asInt()==57)
                else -> menu.navigate(mapOf(72 to "up",80 to "down",75 to "left",77 to "right").getValue(key.asInt()),model,result)
            }
            repeat(12) { if(menu.stage==ShopMenu.Stage.TRADE) result=menu.update(1f/70f,model,result) }
        }
        return result
    }
    @Test fun `shop unsigned literals and pause positions match native parser`() {
        for(row in report.get("parser")) {
            val input=row.get("input").asIntArray();val machine=EventMachine(program(input[0],input[1]),IntArray(300),7,true)
            val request=assertIs<EventMachine.Stop.Shop>(machine.run())
            assertEquals(row.get("args").asIntArray().toList(),listOf(request.priceTable,request.kind))
            assertEquals(request.offset+1+row.getInt("advanced"),machine.offset)
            assertSame(request,machine.run());assertEquals(0,machine.variables()[99])
            machine.resumeService();assertIs<EventMachine.Stop.Finished>(machine.run());assertEquals(456,machine.variables()[99])
            assertIs<EventMachine.Stop.Unhandled>(EventMachine(program(input[0],input[1]),IntArray(300),7).run())
        }
    }
    @Test fun `repair stages and all 193 native trades resume the same event and preserve entire saves`() {
        var count=0
        for(row in report.get("cases")) {
            val before=bytes(row.getString("before"));val source=DosSave.parse(before)
            val code=if(row.has("raw")) EventArchive(bytes(row.getString("raw"))) else archive
            val script=assertNotNull(FieldScript.prepare(code,source,row.getInt("argument"),opening.get("maps"),characters,false,rewards=rewards,canOpenShop={ FieldShops.supported(source,it) }),row.getString("name"))
            for(expected in row.get("requests")) {
                val request=script.next();snapshot(script,expected);assertSame(request,script.next())
                when(request) {
                    is EventMachine.Stop.Dialogue -> { assertEquals("dialogue",expected.getString("kind"));assertContentEquals(bytes(expected.getString("raw")),request.bytes());script.resumeDialogue() }
                    is EventMachine.Stop.Panel -> { assertEquals("panel",expected.getString("kind"));script.resumeService() }
                    is EventMachine.Stop.Reward -> { assertEquals("reward",expected.getString("kind"));script.receiveReward(request);assertContentEquals(bytes(expected.getString("credited")),script.save.bytes());script.resumeService() }
                    is EventMachine.Stop.Shop -> {
                        assertEquals("shop",expected.getString("kind"));assertEquals(expected.getInt("price_table"),request.priceTable);assertEquals(expected.getInt("type"),request.kind)
                        assertContentEquals(bytes(expected.getString("before")),script.save.bytes())
                        val after=if(row.has("trade")) trade(script.save,trades.getValue(row.getString("trade"))) else script.save
                        assertContentEquals(bytes(expected.getString("after")),after.bytes())
                        script.resumeShop(request,after);assertFailsWith<IllegalStateException> { script.resumeShop(request,after) }
                        if(row.has("trade")) count++
                    }
                    else -> fail("잡화점 명령이 원작과 다릅니다.")
                }
            }
            assertIs<EventMachine.Stop.Finished>(script.next());snapshot(script,row.get("end"))
            assertContentEquals(bytes(row.getString("after")),script.save.bytes(),row.getString("name"));assertContentEquals(before,source.bytes())
        }
        assertEquals(193,count)
    }
    @Test fun `unsupported tables kinds and foreign transaction state fail before advancing`() {
        val source=DosSave.parse(bytes(report.get("cases")[4].getString("before"))).withVariables(mapOf(0 to 0))
        for((table,kind) in listOf(0 to 1,1 to 0,65535 to 65535)) {
            val code=program(table,kind)
            assertNull(FieldScript.prepare(code,source,7,opening.get("maps"),characters,false,canOpenShop={ FieldShops.supported(source,it) }))
        }
        assertNull(FieldScript.prepare(program(0,0),source,7,opening.get("maps"),characters,false))
        val script=FieldScript(program(0,0),source,7);val request=assertIs<EventMachine.Stop.Shop>(script.next())
        val before=script.save.bytes()
        for(other in listOf(script.save.withVariables(mapOf(99 to 999)),script.save.travel(4,0,0,144,160,0,0,emptyMap()))) {
            assertFailsWith<IllegalArgumentException> { script.resumeShop(request,other) };assertSame(request,script.next());assertContentEquals(before,script.save.bytes())
        }
        script.resumeShop(request,script.save);assertIs<EventMachine.Stop.Finished>(script.next());assertEquals(456,script.save.variable(99))
    }
}
