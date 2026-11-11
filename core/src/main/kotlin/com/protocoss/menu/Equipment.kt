package com.protocoss.menu

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave

/** 원작 0x15298·0x160D8·0x15E88·0x1A204. 참가 중인 파티원의 여섯 장비 슬롯을 바꾼다. */
class Equipment(val data:JsonValue) {
    data class Item(val id:Int,val name:String,val icon:Int,val category:Int,val allowed:Boolean,
                    val stats:IntArray,val effects:IntArray,val description:String,val allowedBy:BooleanArray)
    private fun parse(rows:JsonValue)=rows.map { Item(it.getInt("id"),it.getString("name"),it.getInt("icon"),
        it.getInt("category"),it.getInt("allowed")!=0,it.get("stats").asIntArray(),it.get("effects").asIntArray(),it.getString("description"),it.get("allowed_by").map { v -> v.asInt()!=0 }.toBooleanArray()) }
    val weapons=parse(data.get("weapons"))
    val items=parse(data.get("items"))
    fun quantity(save:DosSave,id:Int,equipment:Boolean)=save.bytes()[(if(equipment)162 else 123)+id].toInt() and 255
    fun inventory(save:DosSave,equipment:Boolean):List<Item> {
        val bytes=save.bytes();val offset=if(equipment)162 else 123
        return (if(equipment)weapons else items).filter { (bytes[offset+it.id].toInt() and 255)>0 }
    }
    fun party(save:DosSave):List<Int> { val b=save.bytes();return (0..10).filter { b[347+153*it].toInt()!=0 } }
    fun equipped(save:DosSave,character:Int=0,slot:Int=2):Int {
        require(character in 0..10 && slot in 0..5)
        return save.bytes()[473+153*character+slot].toInt() and 255
    }
    fun candidates(save:DosSave,character:Int=0,slot:Int=2):List<Item> {
        require(character in 0..10 && slot in 0..5)
        if(character !in party(save)) return emptyList()
        return inventory(save,true).filter { item -> item.allowedBy[character] && when(slot) {
            2 -> item.category==2 || item.category==5
            4,5 -> item.category==4 && item.id !in listOf(149,150,158,159,161,162)
            else -> item.category==slot
        } }
    }
    fun canEquip(save:DosSave,id:Int,character:Int=0,slot:Int=2):Boolean {
        if(candidates(save,character,slot).none { it.id==id }) return false
        return when {
            slot==2 && weapons[id].category==5 -> equipped(save,character,3)==255
            slot==3 -> equipped(save,character,2).let { it==255 || weapons[it].category!=5 }
            id>=163 && slot==4 -> equipped(save,character,5) !in 163..174
            // 원작 0x15FB3은 반대쪽이 아닌 현재 5번 슬롯의 상한을 비교한다.
            id>=163 && slot==5 -> !(equipped(save,character,4)>=163 && equipped(save,character,5)<=174)
            else -> true
        }
    }
    fun stats(save:DosSave,character:Int=0):IntArray {
        require(character in 0..10)
        val bytes=save.bytes();return IntArray(6) { word(bytes,362+153*character+it*4) }
    }
    fun beginWeapon(save:DosSave)=beginSlot(save,0,2)
    /** 선택창 진입 때 먼저 해제한다. Esc로 돌아와도 해제 상태와 구슬 해제 결과를 유지한다. */
    fun beginSlot(save:DosSave,character:Int,slot:Int):DosSave {
        require(character in party(save) && slot in 0..5)
        val bytes=save.bytes();val old=equipped(save,character,slot)
        if(old!=255) {
            require(old in weapons.indices);bytes[162+old]=(bytes[162+old]+1).toByte();bytes[473+153*character+slot]=(-1).toByte()
            if(old>=163) bytes[387+153*character]=0
        }
        calculate(bytes,character)
        return DosSave.parse(bytes)
    }
    fun equip(save:DosSave,id:Int,character:Int=0,slot:Int=2):DosSave {
        require(equipped(save,character,slot)==255 && canEquip(save,id,character,slot)) { "장착할 수 없는 장비입니다." }
        val bytes=save.bytes();bytes[473+153*character+slot]=id.toByte();bytes[162+id]=(bytes[162+id]-1).toByte()
        if(id>=163) bytes[387+153*character]=(id-162).toByte()
        calculate(bytes,character)
        return DosSave.parse(bytes)
    }
    fun preview(save:DosSave,id:Int,character:Int=0,slot:Int=2):IntArray {
        require(candidates(save,character,slot).any { it.id==id })
        return calculate(save.bytes(),character,id,slot,true)
    }
    private fun calculate(bytes:ByteArray,character:Int,candidate:Int?=null,candidateSlot:Int=2,preview:Boolean=false):IntArray {
        val values=IntArray(6) { word(bytes,360+character*153+it*4) }
        for(slot in 0..3) {
            val id=if(slot==candidateSlot && candidate!=null) candidate else bytes[473+character*153+slot].toInt() and 255
            if(id==255) continue
            require(id in weapons.indices)
            for(j in values.indices) values[j]=(values[j]+(if(preview) weapons[id].stats[j] and 65535 else weapons[id].stats[j])).let { if(preview) it else it and 65535 }
        }
        for(slot in 4..5) {
            val id=if(slot==candidateSlot && candidate!=null) candidate else bytes[473+character*153+slot].toInt() and 255
            if(id==255 || id>=163) continue
            for(j in values.indices) {
                val effect=weapons[id].effects[j]
                if(effect!=0) values[j]+=when(j) { in 0..3 -> ((values[j] and 65535)*.2).toInt();4 -> if(effect==4)1 else 2;else -> 1 }
                if(!preview) values[j]=values[j] and 65535
            }
        }
        return IntArray(6) { j -> (values[j] and 65535).also { if(!preview) putWord(bytes,362+character*153+j*4,it) } }
    }
    companion object {
        private fun word(b:ByteArray,i:Int)=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
        private fun putWord(b:ByteArray,i:Int,value:Int) { b[i]=value.toByte();b[i+1]=(value ushr 8).toByte() }
    }
}
