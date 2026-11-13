package com.protocoss.field

import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 원작 0x1255C·0x12F4C의 참가·이탈. 반환 수량은 8비트로 넘치며 상한을 붙이지 않는다. */
object FieldParty {
    fun change(save:DosSave,character:Int,joining:Boolean):DosSave {
        require(character in 0..10) { "파티원 번호가 원작 자료 범위를 벗어났습니다." }
        val bytes=save.bytes();val offset=153*character
        if(joining) {
            require((0..10).count { bytes[347+it*153].toInt()==1 }<5 && bytes[347+offset].toInt()!=1) { "원작에서 합류할 수 없는 파티 상태입니다." }
            val form=bytes[387+offset].toInt() and 255
            require(form in 0..12) { "변신 번호가 원작 자료 범위를 벗어났습니다." }
            bytes[347+offset]=1;bytes[472+offset]=1
            val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(352+offset,buffer.getShort(354+offset));buffer.putShort(356+offset,buffer.getShort(358+offset))
            if(form!=0) bytes[447+offset+form]=bytes[459+offset+form]
        } else {
            require(character!=0 && bytes[347+offset].toInt()!=0) { "원작에서 이탈할 수 없는 파티 상태입니다." }
            require((477+offset..478+offset).all { (bytes[it].toInt() and 255) in 0..174 || bytes[it].toInt()==-1 }) { "반환할 장비 번호가 소지품 범위를 벗어났습니다." }
            bytes[347+offset]=0
            for(slot in 477+offset..478+offset) {
                val item=bytes[slot].toInt() and 255
                if(item in 163..174) { bytes[162+item]=(bytes[162+item]+1).toByte();bytes[slot]=(-1).toByte();bytes[387+offset]=0 }
            }
        }
        return DosSave.parse(bytes)
    }
}
