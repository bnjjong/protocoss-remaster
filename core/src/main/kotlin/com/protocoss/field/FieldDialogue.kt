package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue

/** 선택한 주민만 멈추고 나머지 주민은 계속 움직이는 원작 필드 대화. */
class FieldDialogue(val npcIndex:Int, private val pages:JsonValue) {
    var pageIndex=0; private set
    val page get()=pages[pageIndex]
    var visibleCharacters=0; private set
    var finished=false; private set
    val isMoneyReward get()=page.getString("kind","")=="money"
    val isReward get()=page.getString("kind","")=="reward" || isMoneyReward
    private val nativeReward get()=page.getBoolean("native_reward",false)
    var rewardLeaving=false;private set
    private var exitStep=0
    private var exitSteps=0
    var rewardX=if(nativeReward)318f else 334f; private set
    val pageComplete get()=if(isReward) rewardX<=14f else visibleCharacters>=page.getString("text").length
    fun continueWith(next:JsonValue) {
        check(finished && next.size>0)
        for(page in next) pages.addChild(com.badlogic.gdx.utils.JsonReader().parse(page.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)))
        pageIndex++;visibleCharacters=0;finished=false;accumulator=0f;rewardX=if(nativeReward)318f else 334f;rewardLeaving=false;exitStep=0
    }
    private var accumulator=0f
    fun update(delta:Float) {
        accumulator+=delta*70f
        val count=accumulator.toInt();accumulator-=count
        if(nativeReward && rewardLeaving) {
            exitStep=minOf(exitSteps,exitStep+count);rewardX=14f-16f*minOf(exitStep,exitSteps-1)
            if(exitStep==exitSteps) finished=true
            return
        }
        if(isReward) { rewardX=(rewardX-count*16).coerceAtLeast(14f);visibleCharacters=page.getString("text").length;return }
        visibleCharacters=(visibleCharacters+count).coerceAtMost(page.getString("text").length)
    }
    fun advance() {
        if(nativeReward) {
            if(!rewardLeaving) {
                rewardLeaving=true;rewardX=14f;accumulator=0f;exitStep=0
                exitSteps=page.getString("text").toByteArray(java.nio.charset.Charset.forName("x-Johab")).size/2+1
            }
            return
        }
        if(!pageComplete) { if(isReward) rewardX=14f;visibleCharacters=page.getString("text").length;return }
        if(pageIndex+1==pages.size) { finished=true;return }
        val previous=page;pageIndex++
        val retained=previous.getString("text").substringAfterLast('\n')+"\n"
        visibleCharacters=if(page.getString("speaker")==previous.getString("speaker") && page.getString("text").startsWith(retained)) retained.length else 0
        accumulator=0f
        rewardX=334f
    }
    companion object {
        fun pages(data:JsonValue,event:Int,stage:Int):JsonValue? = data.get("npc_dialogues")?.get(event.toString())?.get(when { stage<3->"0";stage<4->"3";else->"4" })
    }
}
