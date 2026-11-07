package com.protocoss.field

import com.protocoss.save.DosSave

/** AREA00 그룹 1·2·3의 마당·연결 길·마을 출구. 첫 조우는 호출자가 재생한다. */
object TownTravel {
    fun travel(save: DosSave,event: Int): DosSave? {
        if(save.area!=0) return null
        val group=save.variable(0)
        if(save.map==0 && group==1 && event==2) {
            if(save.variable(7)==0) return save.travel(3,0,0,120,16,8,0,mapOf(0 to 2,1 to 2,4 to 25))
            return town(save,if(save.variable(8)<3) 4 else 12,2)
        }
        if(save.map==3 && group==2) return when(event) {
            0 -> yard(save,0)
            1 -> town(save,4,1)
            else -> null
        }
        if(save.map in setOf(4,12) && group==3 && event==1) return yard(save,1)
        return null
    }
    private fun town(save:DosSave,map:Int,argument:Int)=save.travel(map,0,0,88,24,8,0,mapOf(0 to 3,1 to argument,4 to 24)).withLocation("엘가임 마을")
    private fun yard(save:DosSave,argument:Int):DosSave {
        val moved=save.travel(0,40,25,304,184,12,0,mapOf(0 to 1,1 to argument,4 to 25))
        return if(argument==1) moved.withLocation("벨슨그의 오두막") else moved
    }
}
