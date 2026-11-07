package com.protocoss.field

import com.protocoss.save.DosSave

/** AREA00.G&M 그룹 0·1의 출입구(인자 1). 시작 연출은 호출하지 않는다. */
object CabinDoors {
    fun travel(save: DosSave, event: Int): DosSave? {
        if(save.area!=0 || event!=1) return null
        if(save.map in 1..2 && save.variable(0)==0)
            return save.travel(0,24,16,160,104,8,1,mapOf(0 to 1,1 to 1,4 to 25))
        if(save.map==0 && save.variable(0)==1) {
            val stage=save.variable(8)
            // 단계 2의 AUTOANI.006은 호출자가 먼저 재생한다.
            if(stage==2) return save.travel(1,0,0,200,120,8,0,mapOf(0 to 0,1 to 7,4 to 25,8 to 3)).withoutEnkidu()
            return save.travel(if(stage<3) 2 else 1,0,0,160,184,0,0,mapOf(0 to 0,1 to 1,4 to 25))
        }
        return null
    }
}
