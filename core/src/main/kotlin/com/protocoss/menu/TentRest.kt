package com.protocoss.menu

import com.protocoss.save.DosSave

/** 원작 0x176AC·0x16F38. 지형이 아닌 이벤트 변수 2가 정확히 1일 때 허용한다. */
object TentRecovery {
    fun canUse(save:DosSave)=save.variable(2)==1 && (save.bytes()[160].toInt() and 255)>0
    fun recover(save:DosSave):DosSave {
        require(canUse(save))
        val b=save.bytes()
        fun heal(current:Int,maximum:Int) { put(b,current,maxOf(word(b,current),(word(b,maximum)*.8).toInt())) }
        for(c in 0..10) {
            val off=153*c
            if(b[347+off].toInt()==0) continue
            heal(352+off,354+off);heal(356+off,358+off)
            for(i in 0..11) {
                val current=b[448+off+i].toInt() and 255;val maximum=b[460+off+i].toInt() and 255
                b[448+off+i]=maxOf(current,(maximum*.8).toInt()).toByte()
            }
            b[472+off]=1
        }
        // 원작은 저장의 추가 캐릭터 일곱 레코드도 활성 상태일 때 체력을 회복한다.
        for(i in 0..6) if(b[2020+46*i].toInt()!=0) heal(2058+46*i,2060+46*i)
        return DosSave.parse(b)
    }
    /** 원작은 휴식을 끝내고 음악·화면을 복구한 뒤에 한 개를 차감한다. */
    fun consume(save:DosSave):DosSave {
        require(canUse(save));val b=save.bytes();b[160]=(b[160]-1).toByte();return DosSave.parse(b)
    }
    private fun word(b:ByteArray,i:Int)=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
    private fun put(b:ByteArray,i:Int,v:Int) { b[i]=v.toByte();b[i+1]=(v ushr 8).toByte() }
}

/** 팔레트·음량 단계는 원작의 64↔0을 따르고, 전환은 앱의 70Hz 시계로 진행한다. */
class TentRest(private val changed:(Phase)->Unit={}) {
    enum class Phase { MENU_OUT, SLEEP_IN, WAIT, SLEEP_OUT, RETURN_IN, DONE }
    var phase=Phase.MENU_OUT;private set
    var level=64;private set
    var paused=false
    private var frame=0
    private var accumulator=0.0
    fun continueRest() { if(phase==Phase.WAIT && !paused) transition(Phase.SLEEP_OUT) }
    fun update(delta:Float) {
        if(paused || phase==Phase.DONE) return
        accumulator+=delta.coerceAtLeast(0f).toDouble()*70
        while(accumulator>=1) {
            accumulator-=1
            if(phase==Phase.WAIT) continue
            frame++
            if(frame>=65) transition(when(phase) {
                Phase.MENU_OUT->Phase.SLEEP_IN;Phase.SLEEP_IN->Phase.WAIT
                Phase.SLEEP_OUT->Phase.RETURN_IN;else->Phase.DONE
            }) else level=if(phase==Phase.MENU_OUT || phase==Phase.SLEEP_OUT)64-frame else frame
            if(phase==Phase.DONE) break
        }
    }
    private fun transition(next:Phase) {
        phase=next;frame=0;level=if(next in listOf(Phase.SLEEP_IN,Phase.RETURN_IN))0 else 64
        changed(next)
    }
    fun volume()=(level*127/64)/127f
}
