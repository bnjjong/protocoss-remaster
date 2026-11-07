package com.protocoss.field

import java.nio.charset.Charset

/** 원작 0x1EB30: 위쪽 장소명 진입→키 입력 대기→왼쪽 퇴장. 좌표는 여백 16픽셀을 제외한다. */
class PlaceBanner(val name:String) {
    private val byteLength=name.toByteArray(Charset.forName("x-Johab")).size
    private val target=159f-byteLength*4f
    var x=302f;private set
    var waitingForInput=false;private set
    var finished=false;private set
    private var exiting=false
    private var exitFrames=0
    private var exitTotal=0
    private var accumulator=0f
    fun advance() {
        if(waitingForInput) { waitingForInput=false;exiting=true;x=14f;exitFrames=byteLength/2+1+((target+16)/16).toInt();exitTotal=exitFrames }
    }
    fun update(delta:Float) {
        accumulator+=delta
        while(accumulator>=1f/70f && !finished && !waitingForInput) {
            accumulator-=1f/70f
            if(exiting) {
                if(exitFrames==0) finished=true
                else { x=14f-16f*(exitTotal-exitFrames);exitFrames-- }
            }
            else { x=(x-16).coerceAtLeast(target);if(x==target) waitingForInput=true }
        }
        if(waitingForInput) accumulator=0f
    }
}
