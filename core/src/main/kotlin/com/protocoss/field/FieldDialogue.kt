package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue

/** 선택한 주민만 멈추고 나머지 주민은 계속 움직이는 원작 필드 대화. */
class FieldDialogue(val npcIndex:Int, private val pages:JsonValue) {
    var pageIndex=0; private set
    val page get()=pages[pageIndex]
    var visibleCharacters=0; private set
    var finished=false; private set
    val pageComplete get()=visibleCharacters>=page.getString("text").length
    private var accumulator=0f
    fun update(delta:Float) {
        accumulator+=delta*70f
        val count=accumulator.toInt();accumulator-=count
        visibleCharacters=(visibleCharacters+count).coerceAtMost(page.getString("text").length)
    }
    fun advance() {
        if(!pageComplete) { visibleCharacters=page.getString("text").length;return }
        if(pageIndex+1==pages.size) { finished=true;return }
        val previous=page;pageIndex++
        val retained=previous.getString("text").substringAfterLast('\n')+"\n"
        visibleCharacters=if(page.getString("speaker")==previous.getString("speaker") && page.getString("text").startsWith(retained)) retained.length else 0
        accumulator=0f
    }
    companion object {
        fun pages(data:JsonValue,event:Int,stage:Int):JsonValue? = data.get("npc_dialogues")?.get(event.toString())?.get(when { stage<3->"0";stage<4->"3";else->"4" })
    }
}
