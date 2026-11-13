package com.protocoss.field

/** 원작 0x11B20의 연출 접근. 현재 연결한 오두막·합류 맵의 수평·수직 보행을 처리한다. */
class FieldApproach(
    var x:Int, var y:Int, initialPose:Int, initialLayer:Int,
    var scrollX:Int, var scrollY:Int,
    private val targetX:Int, private val targetY:Int,
    private val targetScrollX:Int, private val targetScrollY:Int,
    private val maxScrollX:Int, private val maxScrollY:Int,
    private val step:Int,
    private val allowed:(Int,Int,Int,Int)->FieldRules.Step,
    private val tickNpcs:(Int,Int)->Unit = { _,_-> }
) {
    companion object {
        fun npcVisible(x:Int,y:Int,scrollX:Int,scrollY:Int)=x-scrollX in -15..311 && y-scrollY in -15..191
    }
    var pose=initialPose; private set
    var layer=initialLayer; private set
    var direction=0; private set
    var finished=false; private set
    var ticks=0; private set
    private var accumulator=0f
    private var remaining=0
    private var vector=0
    private var scrolling=false
    init {
        require(step in listOf(1,2,4,8))
        require(listOf(x,y,scrollX,scrollY,targetX,targetY,targetScrollX,targetScrollY).all { it%8==0 })
    }
    fun update(delta:Float) {
        require(delta.isFinite() && delta>=0)
        accumulator+=delta
        while(!finished && accumulator>=1f/70f) {
            accumulator-=1f/70f;ticks++
            var arrived=false
            if(remaining==0) {
                val screenX=x-scrollX+16;val screenY=y-scrollY+16
                val tx=targetX-targetScrollX+16;val ty=targetY-targetScrollY+16
                val requested=when {
                    scrollX<targetScrollX || screenX<tx -> 2
                    scrollX>targetScrollX || screenX>tx -> 8
                    scrollY<targetScrollY || screenY<ty -> 4
                    scrollY>targetScrollY || screenY>ty -> 1
                    else -> 0
                }
                arrived=requested==0
                val result=if(arrived) FieldRules.Step(0,layer) else allowed(x,y,requested,layer)
                require(result.direction in listOf(0,1,2,4,8)) { "이 연출의 경사 접근은 연결하지 않았습니다." }
                vector=result.direction;layer=result.layer
                if(vector==0) {
                    pose=if(arrived) pose/4*4 else when(requested) { 1->0;2->4;4->8;else->12 }
                    direction=0
                }
                else {
                    val base=when(vector) { 1->0;2->4;4->8;else->12 }
                    pose=(pose+1).takeIf { it in base..base+3 } ?: base
                    scrolling=when(vector) {
                        1 -> screenY==104 && scrollY!=0
                        2 -> screenX==160 && scrollX!=maxScrollX
                        4 -> screenY==104 && scrollY!=maxScrollY
                        else -> screenX==160 && scrollX!=0
                    }
                    direction=if(scrolling) vector else 0
                    remaining=8
                }
            }
            val dx=FieldRules.dx(vector)*step;val dy=FieldRules.dy(vector)*step
            if(remaining>0 && !scrolling) { x+=dx;y+=dy }
            // 원작은 화면 안 보행 뒤, 스크롤 보행 전에 주민을 갱신한다.
            tickNpcs(x,y)
            if(remaining>0) {
                if(scrolling) { x+=dx;y+=dy;scrollX+=dx;scrollY+=dy }
                remaining-=step
            }
            if(arrived) finished=true
        }
    }
}
