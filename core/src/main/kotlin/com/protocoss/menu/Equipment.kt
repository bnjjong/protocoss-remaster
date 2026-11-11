package com.protocoss.menu

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave

/** 원작 0x15298·0x160D8·0x15E88·0x1A204. 카만의 무기 슬롯만 바꾼다. */
class Equipment(val data:JsonValue) {
    data class Item(val id:Int,val name:String,val icon:Int,val category:Int,val allowed:Boolean,
                    val stats:IntArray,val effects:IntArray,val description:String)
    private fun parse(rows:JsonValue)=rows.map { Item(it.getInt("id"),it.getString("name"),it.getInt("icon"),
        it.getInt("category"),it.getInt("allowed")!=0,it.get("stats").asIntArray(),it.get("effects").asIntArray(),it.getString("description")) }
    val weapons=parse(data.get("weapons"))
    val items=parse(data.get("items"))
    fun quantity(save:DosSave,id:Int,equipment:Boolean)=save.bytes()[(if(equipment)162 else 123)+id].toInt() and 255
    fun inventory(save:DosSave,equipment:Boolean):List<Item> {
        val bytes=save.bytes();val offset=if(equipment)162 else 123
        return (if(equipment)weapons else items).filter { (bytes[offset+it.id].toInt() and 255)>0 }
    }
    fun equipped(save:DosSave)=save.bytes()[475].toInt() and 255
    fun candidates(save:DosSave)=inventory(save,true).filter { it.allowed && it.category in listOf(2,5) }
    fun canEquip(save:DosSave,id:Int)=candidates(save).any { it.id==id } &&
        (weapons[id].category!=5 || save.bytes()[476].toInt()==-1)
    fun stats(save:DosSave):IntArray { val bytes=save.bytes();return IntArray(6) { word(bytes,362+it*4) } }

    /** 선택창 진입 때 먼저 해제한다. Esc로 돌아와도 해제된 상태를 유지한다. */
    fun beginWeapon(save:DosSave):DosSave {
        val bytes=save.bytes();val old=equipped(save)
        if(old!=255) { require(old in weapons.indices);bytes[162+old]=(bytes[162+old]+1).toByte();bytes[475]=(-1).toByte() }
        calculate(bytes)
        return DosSave.parse(bytes)
    }
    fun equip(save:DosSave,id:Int):DosSave {
        require(equipped(save)==255 && canEquip(save,id)) { "장착할 수 없는 무기입니다." }
        val bytes=save.bytes();bytes[475]=id.toByte();bytes[162+id]=(bytes[162+id]-1).toByte();calculate(bytes)
        return DosSave.parse(bytes)
    }
    fun preview(save:DosSave,id:Int):IntArray {
        require(candidates(save).any { it.id==id })
        return calculate(save.bytes(),id,true)
    }
    private fun calculate(bytes:ByteArray,candidate:Int?=null,preview:Boolean=false):IntArray {
        val values=IntArray(6) { word(bytes,360+it*4) }
        for(slot in 0..3) {
            val id=if(slot==2 && candidate!=null) candidate else bytes[473+slot].toInt() and 255
            if(id==255) continue
            require(id in weapons.indices)
            for(j in values.indices) values[j]=(values[j]+(if(preview) weapons[id].stats[j] and 65535 else weapons[id].stats[j])).let { if(preview) it else it and 65535 }
        }
        for(slot in 4..5) {
            val id=bytes[473+slot].toInt() and 255
            if(id==255 || id>=163) continue
            for(j in values.indices) {
                val effect=weapons[id].effects[j]
                if(effect!=0) values[j]+=when(j) { in 0..3 -> ((values[j] and 65535)*.2).toInt();4 -> if(effect==4)1 else 2;else -> 1 }
                if(!preview) values[j]=values[j] and 65535
            }
        }
        return IntArray(6) { j -> (values[j] and 65535).also { if(!preview) putWord(bytes,362+j*4,it) } }
    }
    companion object {
        private fun word(b:ByteArray,i:Int)=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
        private fun putWord(b:ByteArray,i:Int,value:Int) { b[i]=value.toByte();b[i+1]=(value ushr 8).toByte() }
    }
}
