package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import com.protocoss.script.EventMachine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 원본 AUTOANI와 이미 검증한 연출 자산의 대응. 현재 합류 005·쪽지 006만 연결한다. */
class FieldStory(private val data:JsonValue,private val opening:JsonValue) {
    class Plan internal constructor(val source:JsonValue) {
        val program get()=source.getInt("program")
        val events get()=source.get("events").associate { it.name.toInt() to it.asInt() }
    }
    fun plan(request:EventMachine.Stop.Animation,save:DosSave):Plan {
        require(save.area==0)
        val row=data.get("animations").firstOrNull { it.getInt("id")==request.id } ?: error("이 연출은 연결하지 않았습니다.")
        require(request.mode==row.getInt("mode") && request.music==row.getInt("music")) { "이 연출의 모드·음악 조합은 연결하지 않았습니다." }
        val program=opening.get("programs")[row.getInt("program")]
        require(request.mode!=0 || save.map==row.getInt("map")) { "현재 맵에서 이어지는 연출이 아닙니다." }
        require(program.get("commands").filter { it.getString("op")=="L" }.all { it.get("value")[0].asInt()==0 }) { "이 연출의 카메라 이동은 연결하지 않았습니다." }
        return Plan(row)
    }
    fun begin(save:DosSave,request:EventMachine.Stop.Animation,plan:Plan):DosSave {
        val bytes=save.bytes();val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val row=plan.source
        val sx=row.get("scroll")[0].asInt();val sy=row.get("scroll")[1].asInt()
        buffer.putInt(100,sx);buffer.putInt(104,sy)
        if(request.mode==0) {
            val first=row.get("start");buffer.putInt(108,first.getInt("x")-sx*8+16);buffer.putInt(112,first.getInt("y")-sy*8+16);bytes[116]=0
        } else buffer.putInt(96,row.getInt("map"))
        return DosSave.parse(bytes)
    }
    fun finish(save:DosSave,request:EventMachine.Stop.Animation,plan:Plan):DosSave {
        if(request.mode!=0) return save
        val row=plan.source;val hero=row.get("hero");val sx=row.get("scroll")[0].asInt();val sy=row.get("scroll")[1].asInt()
        return save.travel(save.map,sx,sy,hero[0].asInt()-sx*8+16,hero[1].asInt()-sy*8+16,hero[2].asInt(),save.layer,emptyMap())
    }
}
