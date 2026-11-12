package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue

/** 원작 0x132A4·0x133E0의 다섯 안내창 사각형을 한 틱씩 표시한다. */
class FieldPanel(data:JsonValue,val opening:Boolean) {
    private val frames=data.get("panel_open").map { it.asIntArray() }.let { if(opening) it else it.reversed() }
    var step=0;private set
    val rectangle get()=frames[minOf(step,frames.lastIndex)].copyOf()
    val finished get()=step>=frames.size
    private var accumulator=0f
    fun update(delta:Float) {
        require(delta.isFinite() && delta>=0f)
        accumulator+=delta*70f
        val count=accumulator.toInt();accumulator-=count;step=minOf(frames.size,step+count)
    }
}
