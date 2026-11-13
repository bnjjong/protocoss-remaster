package com.protocoss.battle

/** 원작 IRQ의 0x6155D 한 번을 한 틱으로 취급한다. 실제 시간 간격과 피해 처리는 별도 연결한다. */
class BattleClock(val party:List<Actor>,val enemies:List<Actor>,private val delays:List<Int>) {
    class Actor(var status:Int,val speed:Int,var gauge:Int,val hp:Int=0,val maxHp:Int=0,val mp:Int=0,val maxMp:Int=0) {
        var ticks=0L;internal set
        var hpBar=0;internal set
        var mpBar=0;internal set
        var charging=false
    }
    var time=0L;private set
    private val queue=IntArray(6)
    var head=0;private set
    var tail=0;private set
    init { require(party.size<=5 && enemies.size<=5 && delays.size>=11) }
    private fun alive(a:Actor)=a.status !in listOf(0,5)
    private fun able(a:Actor)=a.status !in listOf(0,2,5)
    private fun denominator()=40+delays[party.count(::able)+enemies.count(::alive)]
    fun queueSnapshot()=queue.copyOf()
    fun tick(enabled:Boolean=true) {
        time=(time+1) and 0xffffffffL
        if(enabled) (party+enemies).forEach { it.ticks=(it.ticks+1) and 0xffffffffL }
    }
    private fun bars(a:Actor) {
        fun bar(v:Int,m:Int)=if(m==0)0 else (v/(m/17f).toDouble()+.5).toInt()
        a.hpBar=bar(a.hp,a.maxHp);a.mpBar=bar(a.mp,a.maxMp)
    }
    private fun advance(a:Actor) {
        if(a.speed==0)return
        val threshold=(1.0+(405-a.speed).toDouble()/denominator()).toFloat()
        if(a.ticks.toDouble()>threshold) {
            a.ticks=0
            if(!a.charging)a.gauge++
        }
    }
    /** 명령창이 열린 동안 준비된 파티원을 원작의 여섯 칸 대기열에 넣는다. */
    fun refreshParty() {
        party.forEachIndexed { i,a ->
            bars(a)
            if(able(a) && a.gauge<28) {
                advance(a)
                if(a.gauge>=28) { queue[tail]=i;tail=(tail+1)%6;a.gauge=28 }
            }
        }
    }
    fun nextParty():Int? {
        while(head!=tail) {
            val i=queue[head];head=(head+1)%6
            // 0x6456C는 마지막 대기 항목에서 전체 검색으로 돌아간다.
            if(head==tail)break
            if(party[i].status !in listOf(2,5))return i
        }
        head=0;tail=0
        for((i,a) in party.withIndex()) {
            bars(a)
            if(!able(a))continue
            if(a.gauge==28)return i
            advance(a)
            if(a.gauge>=28) { a.gauge=28;return i }
        }
        return null
    }
    fun nextEnemy():Int? {
        for((i,a) in enemies.withIndex()) {
            if(!able(a) || a.speed==0)continue
            val threshold=(404-a.speed)/denominator()+1
            if(a.ticks>(threshold.toLong() and 0xffffffffL)) {
                a.ticks=0;a.gauge++
                if(a.gauge>=28) { a.gauge=28;return i }
            }
        }
        return null
    }
    /** 행동 본체를 마친 뒤 원작 호출자가 복구하는 게이지. 행동의 효과는 여기서 계산하지 않는다. */
    fun finishParty(i:Int) { party[i].gauge=0 }
    fun finishEnemy(i:Int) { enemies[i].gauge=0 }
}
