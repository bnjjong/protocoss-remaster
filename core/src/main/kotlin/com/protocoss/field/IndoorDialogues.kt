package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 실내 주민은 맵·이벤트 그룹·인자를 함께 확인해 대화를 고른다. */
class IndoorDialogues(private val data:JsonValue) {
    class Conversation(private val value:JsonValue) {
        private var paid=false
        val pages:JsonValue get()=value.get("pages")
        val reward:Boolean get()=value.getBoolean("reward")
        fun receive(save:DosSave):DosSave {
            if(!reward || paid) return save
            paid=true
            val raw=save.bytes();val buffer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            // 원작 0x12A72: int32 덧셈을 먼저 한 뒤 999,999 상한을 적용한다.
            buffer.putInt(119,minOf(buffer.getInt(119)+50,999999))
            return DosSave.parse(raw)
        }
        fun finish(save:DosSave)=save.withVariables(value.get("variables").associate { it.name.toInt() to it.asInt() })
    }
    fun begin(save:DosSave,event:Int):Conversation? {
        if(save.area!=0) return null
        val entry=data.get("entries").firstOrNull { it.getInt("map")==save.map && it.getInt("group")==save.variable(0) && it.getInt("event")==event } ?: return null
        val stage=minOf(save.variable(8),3)
        val key=when {
            save.map==10->"$stage-${if(save.variable(50)==0)0 else 1}"
            save.map==7 && event==1->minOf(save.variable(7),2).toString()
            else->stage.toString()
        }
        return Conversation(entry.get("variants").get(key))
    }
}
