package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave

/** 오두막의 정면 조사와 AUTOANI.002 벽난로 반응. */
class CabinInteraction(private val data:JsonValue) {
    private fun inside(save:DosSave)=save.area==0 && save.map in 1..2 && save.variable(0)==0
    fun pages(save:DosSave,event:Int):JsonValue? =
        if(inside(save) && event in 4..10) data.get("dialogues").get(event.toString()) else null
    fun fireDestination(save:DosSave,event:Int):DosSave? {
        if(!inside(save) || event!=15) return null
        val hero=data.get("field_native_checkpoints").get("6").last().get("actors")[0]
        return save.travel(save.map,save.scrollX,save.scrollY,hero.getInt("x")+16,hero.getInt("y")+16,
            hero.getInt("frame"),hero.getInt("mask"),mapOf(1 to 8))
    }
}
