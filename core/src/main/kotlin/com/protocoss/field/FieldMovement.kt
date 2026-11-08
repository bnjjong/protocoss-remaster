package com.protocoss.field

/** 70Hz, 8픽셀 단위 보행. 한 걸음을 시작한 뒤에는 키를 놓아도 끝까지 진행한다. */
class FieldMovement(private val rules: FieldRules, val map: Int, var layer: Int, initialPose: Int = 8) {
    var pose = initialPose; private set
    var x = 0f; private set
    var y = 0f; private set
    var doorOpen = false
    var direction = 0; private set
    var moving = false; private set
    val atRest get() = remaining == 0
    var npcs: FieldNpcs? = null
    private var accumulator = 0f
    private var remaining = 0
    private var vector = 0
    fun place(x: Float, y: Float) { this.x=x;this.y=y;remaining=0;accumulator=0f;moving=false }
    fun faceForTalk() { check(atRest);pose=pose/4*4;moving=false }
    fun face(direction:Int) {
        check(atRest)
        this.direction=direction
        pose=when(direction) { 1->0;2->4;4->8;else->12 };moving=false
    }

    /** 이벤트가 맵을 교체하면 true를 반환하여 남은 프레임의 보행을 중단한다. */
    fun update(delta: Float, input: Int, dash: Boolean, tick: ((Float,Float)->Unit)? = null, event: (Int) -> Boolean): Boolean {
        require(delta.isFinite() && delta >= 0)
        accumulator += delta
        while (accumulator >= 1f/70f) {
            accumulator -= 1f/70f
            tick?.invoke(x,y)
            if (remaining == 0) {
                moving = false
                if (input == 0) continue
                direction = input
                val trigger = rules.event(map,x,y,input,doorOpen)
                    ?: if(input !in listOf(1,2,4,8)) {
                        rules.event(map,x,y,input and 5,doorOpen) ?: rules.event(map,x,y,input and 10,doorOpen)
                    } else null
                if (trigger != null && event(trigger)) return true
                val step = allowed(input)
                val base=when(input) { 1 -> 0;2,3,6 -> 4;4 -> 8;else -> 12 }
                if (step.direction == 0) { pose=base;continue }
                pose=(pose+1).takeIf { it in base..base+3 } ?: base
                layer = step.layer; vector = step.direction; remaining=8
            }
            val amount = minOf(remaining, if(dash) 2 else 1)
            x += FieldRules.dx(vector)*amount; y += FieldRules.dy(vector)*amount
            remaining -= amount;moving=true
        }
        return false
    }

    private fun allowed(input: Int): FieldRules.Step {
        if(input in listOf(1,2,4,8)) return step(x,y,input,layer)
        // 원작의 대각선 번호는 경사로 이동에도 쓰인다. 동시 키 입력은 두 축 모두 검사한다.
        val horizontal = if(FieldRules.dx(input)>0) 2 else 8
        val vertical = if(FieldRules.dy(input)>0) 4 else 1
        fun path(first: Int, second: Int): FieldRules.Step? {
            val a=step(x,y,first,layer)
            if(a.direction!=first) return null
            val b=step(x+FieldRules.dx(first)*8,y+FieldRules.dy(first)*8,second,a.layer)
            return b.takeIf { it.direction==second }
        }
        val a=path(horizontal,vertical);val b=path(vertical,horizontal)
        return if(a!=null && b!=null && a.layer==b.layer) FieldRules.Step(input,a.layer) else FieldRules.Step(0,layer)
    }

    private fun step(x:Float,y:Float,direction:Int,layer:Int)=
        if(npcs?.blocksPlayer(x,y,direction)==true) FieldRules.Step(0,layer)
        else rules.step(map,x,y,direction,layer,doorOpen)
}
