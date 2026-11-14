package com.protocoss.battle

/** 0x5E7B3. 원작은 추첨마다 BIOS 시계의 하위 바이트와 호출 횟수로 난수를 다시 초기화한다. */
class DosBattleRandom(private val clockByte:()->Int,initialCounter:Long=0) {
    var counter=initialCounter and 0xffffffffL;private set
    fun next(bound:Int):Int {
        if(bound<1)return 0
        val clock=clockByte();require(clock in 0..255)
        val seed=(clock+counter) and 0xffffffffL
        counter=(counter+1) and 0xffffffffL
        val value=(seed*0x41c64e6dL+0x3039) and 0xffffffffL
        return ((value ushr 16).toInt() and 0x7fff)%bound
    }
}

/** 원작 0x587C2의 단일 대상 물리 피해. 보호막·반사·추가 주문은 호출자에서 별도로 처리한다. */
object PhysicalDamage {
    data class Attacker(val attack:Int,val magic:Int,var luck:Int,val buff:Int=0,val elements:List<Int> = emptyList())
    data class Defender(var hp:Int,val armor:Int,val evasion:Int,val responses:List<Int>)
    fun strike(a:Attacker,d:Defender,random:DosBattleRandom):Int {
        a.luck=minOf(a.luck,10)
        var damage=if(a.buff!=0)a.magic+a.buff else a.attack*2
        if(d.evasion>random.next(10))return 0
        val tenth=damage/10
        damage=if(a.luck>random.next(10))damage shl 1 else damage+tenth*a.luck
        for(element in a.elements) {
            if(element==0)continue
            require(element in d.responses.indices)
            damage=when(d.responses[element]) { 1->damage shl 1;2->damage/5;else->damage }
        }
        if(damage<d.armor)return 0
        damage-=d.armor
        if(d.hp<damage) { damage=d.hp;d.hp=0 } else d.hp-=damage
        return maxOf(0,damage)
    }
}
