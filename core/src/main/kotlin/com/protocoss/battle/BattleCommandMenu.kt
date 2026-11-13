package com.protocoss.battle

/** 0x5F0BD→0x5EF04의 방향키 분기와 하위 메뉴 반환 대기. 실제 공격/기술은 후속 서비스다. */
class BattleCommandMenu(val partyIndex:Int=0) {
    init { require(partyIndex in 0..4) }
    enum class Direction(val scan:Int) { UP(72),RIGHT(77),DOWN(80),LEFT(75) }
    sealed interface Request {
        data class Submenu(val direction:Direction):Request
        data object RejectedEscape:Request {
            val sound=5
            val effect=27
            val animation=14
        }
    }
    var pending:Request.Submenu?=null;private set
    var closed=false;private set
    val gauge get()=if(closed)0 else 28
    val selected get()=if(closed)65535 else partyIndex
    val mode get()=if(closed)0 else 1
    fun input(scan:Int):Request? {
        check(!closed && pending==null)
        if(scan==1)return Request.RejectedEscape
        val direction=Direction.entries.firstOrNull { it.scan==scan } ?: return null
        return Request.Submenu(direction).also { pending=it }
    }
    /** 0이면 명령창으로 돌아오고, 0이 아니면 호출자가 게이지를 0으로 복구한다. */
    fun resume(completed:Boolean) {
        check(pending!=null && !closed)
        pending=null;closed=completed
    }
}
