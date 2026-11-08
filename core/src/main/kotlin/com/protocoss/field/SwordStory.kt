package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave

/** 잡화점 검 수리와 그 직후 합류의 원작 진행 조건. 일반 거래는 실행하지 않는다. */
class SwordStory(private val data:JsonValue) {
    fun shopPages(save:DosSave,event:Int):JsonValue? {
        if(save.area!=0 || save.map!=5 || save.variable(0)!=4 || event!=2) return null
        return data.get("shop").get(when { save.variable(8)==0->"0";save.variable(8)<3->"1";save.variable(8)==3->"3";else->"4" })
    }
    fun finishShop(save:DosSave)=save.withVariables(mapOf(1 to 2,8 to when(save.variable(8)) { 0->1;3->4;else->save.variable(8) }))
    fun joins(save:DosSave,event:Int)=save.area==0 && save.map==4 && save.variable(0)==3 && event==15 && save.variable(8)==1
    fun joinedState(save:DosSave)=save.withEnkidu().withVariables(mapOf(0 to 3,1 to 17,8 to 2))
    fun townTriggerState(save:DosSave)=if(save.variable(8)==1) joinedState(save) else save.calledEvent(15)
    fun joinDestination(save:DosSave):DosSave {
        val join=data.get("join");val scroll=join.get("scroll");val hero=join.get("hero")
        val sx=scroll[0].asInt()/8;val sy=scroll[1].asInt()/8
        return joinedState(save).travel(4,sx,sy,hero[0].asInt()-sx*8+16,hero[1].asInt()-sy*8+16,
            hero[2].asInt(),0,mapOf(4 to join.getInt("music")))
    }
}
