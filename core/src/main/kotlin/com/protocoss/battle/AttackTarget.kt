package com.protocoss.battle

/** 0x5A3F7의 단일 대상 선택. 살아 있는 적은 원래 슬롯 순서를 유지한다. */
class AttackTarget(enemies:List<Enemy>) {
    data class Enemy(val slot:Int,val x:Int,val y:Int,val status:Int,val cursorX:Int,val cursorY:Int)
    val choices=enemies.filter { it.status !in listOf(0,5) }
    init { require(choices.isNotEmpty()) }
    var index=choices.indices.maxBy { choices[it].x };private set
    val current get()=choices[index]
    var selected:Int?=null;private set
    var cancelled=false;private set
    var closed=false;private set
    fun input(scan:Int) {
        check(!closed)
        when(scan) {
            72,75->index=(index+choices.size-1)%choices.size
            77,80->index=(index+1)%choices.size
            28,57->{ selected=current.slot;closed=true }
            1->{ cancelled=true;closed=true }
        }
    }
}
