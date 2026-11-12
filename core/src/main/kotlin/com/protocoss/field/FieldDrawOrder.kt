package com.protocoss.field

/** 원작 0x243F0의 Y 좌표 비교·교환. 입력은 카만, 맵에 기록된 NPC 순서다. */
object FieldDrawOrder {
    fun indices(y:List<Float>):List<Int> {
        val order=y.indices.toMutableList()
        // 같은 Y끼리는 직접 바꾸지 않는다. 다른 인물과의 교환으로 동률 순서가 바뀔 수 있다.
        for(i in order.indices) for(j in i+1 until order.size) {
            if(y[order[i]]>y[order[j]]) {
                val previous=order[i];order[i]=order[j];order[j]=previous
            }
        }
        return order
    }
}
