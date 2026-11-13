package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue

/** 원작 0x12720은 현재 인물 위치의 3×2 타일을 다음 타일 표로 치환한다. 속성은 유지한다. */
object FieldChests {
    fun allowed(map:JsonValue,x:Int,y:Int)=x>=0 && y>=0 && x/8+3<=map.getInt("width") && y/8+2<=map.getInt("height")
    fun changes(map:JsonValue,cells:IntArray,x:Int,y:Int):JsonValue {
        require(allowed(map,x,y)) { "상자 타일이 맵 밖입니다." }
        return JsonValue(JsonValue.ValueType.array).apply {
            for(row in y/8 until y/8+2) for(column in x/8 until x/8+3) {
                val i=row*map.getInt("width")+column;val tid=(cells[i] and 0xf000) or (map.get("next_tiles")[cells[i] and 4095].asInt() and 4095)
                if(tid!=cells[i]) addChild(JsonValue(JsonValue.ValueType.`object`).apply {
                    addChild("index",JsonValue(i.toLong()));addChild("tid",JsonValue(tid.toLong()));addChild("attr",JsonValue(map.get("cells")[i][1].asLong()))
                })
            }
        }
    }
}
