package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave

/** 원작 0x25304가 실행한 마을 건물의 출입 분기. 실내 퀘스트는 실행하지 않는다. */
class BuildingTravel(json:String) {
    private val data=JsonReader().parse(json)
    private fun route(save:DosSave,event:Int):JsonValue? {
        if(save.area!=0) return null
        val group=save.variable(0)
        val maps=data.get("groups").get(group.toString()) ?: return null
        if(maps.none { it.asInt()==save.map }) return null
        return data.get("routes").firstOrNull { it.getInt("group")==group && it.getInt("argument")==event }
            ?.get("variants")?.get(if(save.variable(8)<3) "0" else "3")
    }
    fun travel(save:DosSave,event:Int):DosSave? {
        val route=route(save,event) ?: return null
        val args=route.get("warp")
        return save.travel(args[0].asInt(),args[1].asInt(),args[2].asInt(),args[3].asInt(),args[4].asInt(),
            args[5].asInt(),args[6].asInt(),mapOf(0 to route.getInt("group"),1 to event,4 to args[7].asInt()))
    }
    fun hasDoorSound(save:DosSave,event:Int)=route(save,event)?.get("effects")?.any { it.asInt()==0 }==true
}
