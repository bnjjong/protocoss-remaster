package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import com.protocoss.script.EventMachine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 원작 0x12924의 아이템·장비·소지금 지급. 수량 덧셈의 넘침도 보존한다. */
class FieldRewards(private val equipment:JsonValue,val data:JsonValue) {
    private fun item(id:Int,category:Int):JsonValue {
        require(category in 0..1)
        val rows=equipment.get(if(category==0) "items" else "weapons")
        require(id in 0 until rows.size) { "보상 품목의 원작 자료가 없습니다." }
        return rows[id]
    }
    fun receive(save:DosSave,request:EventMachine.Stop.Reward):DosSave {
        require(request.count in 0..65535)
        val bytes=save.bytes()
        when(request.category) {
            0,1 -> {
                item(request.item,request.category)
                val offset=(if(request.category==0) 123 else 162)+request.item
                bytes[offset]=minOf(((bytes[offset].toInt() and 255)+request.count) and 255,99).toByte()
            }
            2 -> ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(119,minOf(getInt(119)+request.count,999999))
            }
            else -> error("이 보상 종류는 연결하지 않았습니다.")
        }
        return DosSave.parse(bytes)
    }
    fun pages(request:EventMachine.Stop.Reward):JsonValue {
        require(request.count in 0..65535 && request.category in 0..2)
        val money=request.category==2
        val row=if(money) null else item(request.item,request.category)
        val text=if(money) data.get("formats").getString("money").replace("%d",request.count.toString())
            else data.get("formats").getString("item").replace("%s",row!!.getString("name")).replace("%d",request.count.toString())
        if(row!=null) require(equipment.get("rects").has("common-${row.getInt("icon")}")) { "보상 그림이 없습니다." }
        return JsonValue(JsonValue.ValueType.array).apply {
            addChild(JsonValue(JsonValue.ValueType.`object`).apply {
                addChild("kind",JsonValue(if(money) "money" else "reward"));addChild("speaker",JsonValue(""));addChild("portrait",JsonValue(-1L))
                addChild("text",JsonValue(text));addChild("native_reward",JsonValue(true))
                addChild("category",JsonValue(request.category.toLong()));addChild("count",JsonValue(request.count.toLong()))
                if(row!=null) addChild("icon",JsonValue(row.getInt("icon").toLong()))
            })
        }
    }
}
