package com.protocoss.menu

import com.protocoss.save.DosSave

/** 원작 0x1B844·0x1B2F8의 파티원 대상 사용. 장소·전투 전용 아이템은 별도 복원한다. */
class FieldItems(private val equipment:Equipment) {
    data class Result(val save:DosSave,val consumed:Boolean,val blocked:Boolean=false)
    fun targetable(id:Int):Boolean {
        if(id !in equipment.items.indices) return false
        val row=equipment.data.get("items")[id]
        return row.getInt("category") in listOf(0,2) && row.getInt("operation",0) in listOf(1,2,3,4,5,6,7,8,9,10,17,18)
    }
    fun use(save:DosSave,id:Int,character:Int):Result {
        if(!targetable(id) || character !in equipment.party(save) || equipment.quantity(save,id,false)==0) return Result(save,false,true)
        val b=save.bytes();val offset=153*character
        if((b[472+offset].toInt() and 255)==5 && id!=16) return Result(save,false,true)
        val row=equipment.data.get("items")[id];val amount=row.getInt("amount");var changed=false
        fun heal(current:Int,maximum:Int,full:Boolean=false) {
            val value=word(b,current+offset);val limit=word(b,maximum+offset)
            if(value<limit) {
                put(b,current+offset,if(full)limit else minOf((value+amount) and 65535,limit));changed=true
            }
        }
        fun cure(vararg statuses:Int) {
            if((b[472+offset].toInt() and 255) in statuses) { b[472+offset]=1;changed=true }
        }
        when(row.getInt("operation")) {
            1 -> heal(352,354)
            2 -> heal(356,358)
            3 -> heal(352,354,true)
            4 -> { heal(352,354,true);heal(356,358,true) }
            5 -> cure(2)
            6 -> cure(3)
            7 -> cure(4)
            8 -> cure(2,3,4)
            9 -> cure(5)
            10 -> { heal(352,354);cure(3) }
            17 -> {
                val buffer=java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(348+offset,(buffer.getInt(348+offset)-amount.toShort().toInt()).coerceAtLeast(1));changed=true
            }
            18 -> { put(b,384+offset,word(b,384+offset)+amount);changed=true }
        }
        if(changed) b[123+id]=(b[123+id]-1).toByte()
        return Result(if(changed)DosSave.parse(b) else save,changed)
    }
    private fun word(b:ByteArray,i:Int)=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
    private fun put(b:ByteArray,i:Int,v:Int) { b[i]=v.toByte();b[i+1]=(v ushr 8).toByte() }
}

/** 원작처럼 최대 다섯 참가자를 3열·2행으로 고르며 빈 자리에서는 마지막 참가자로 보정한다. */
class ItemTargetMenu(val item:Int) {
    var index=0;private set
    fun navigate(direction:String,count:Int) {
        require(count in 1..5)
        val x=(index%3+when(direction) { "left" -> -1;"right" -> 1;else -> 0 }).coerceIn(0,2)
        val y=(index/3+when(direction) { "up" -> -1;"down" -> 1;else -> 0 }).coerceIn(0,1)
        index=minOf(y*3+x,count-1)
    }
}
