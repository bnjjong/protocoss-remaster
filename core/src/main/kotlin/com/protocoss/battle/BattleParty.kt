package com.protocoss.battle

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave

/** 원작 0x5F12F·0x602FA의 참가 순서·시작 자세·배치. 전투 결과를 저장에 반영하지 않는다. */
class BattleParty(json:String) {
    val data=JsonReader().parse(json)
    init { require(data.getInt("version")==1) }
    data class Layer(val character:Int,val frame:Int,val x:Int,val y:Int)
    data class Actor(val character:Int,val pose:Int,val role:Int,val effect:Int,val stats:List<Int>,val parameterHex:String,
                     val x:Int,val y:Int,val homeX:Int,val homeY:Int,val hudX:Int,val hudY:Int,val gauge:Int,val anchorY:Int,
                     val layers:List<Layer>)
    private fun word(b:ByteArray,i:Int)=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
    fun prepare(save:DosSave):List<Actor> {
        val b=save.bytes();val participants=(0..10).filter { b[347+it*153]!=0.toByte() }.take(5)
        return participants.mapIndexed { i,c ->
            val p=b.copyOfRange(337+c*153,490+c*153)
            val status=p[135].toInt() and 255
            require(status in 1..5) { "초기화되지 않은 참가자의 전투 상태입니다." }
            val form=p[50].toInt() and 255;require(form in 0..12)
            val pose=when(status) { 5->10;3->1;2->2;else->0 }
            val anchor=data.get("anchors")[participants.size-1][i]
            val poses=data.get("poses").get(c.toString())
            val standing=poses.get("0")[0].getInt("frame")
            val size=data.get("sizes").get(c.toString())[standing]
            val hx=anchor[0].asInt()-size[0].asInt();val hy=anchor[1].asInt()-size[1].asInt()
            val offset=data.get("dead_offsets")[c]
            val x=hx+if(status==5)offset[0].asInt() else 0
            val y=hy+if(status==5)offset[1].asInt() else 0
            Actor(c,pose,if(form==0)77 else data.get("roles")[form].asInt(),if(form==0)0 else 89+form*10,
                listOf(25,29,33,37).map { word(p,it) },p.joinToString("") { "%02x".format(it) },
                x,y,hx,hy,150+(participants.size-1)*32-i*64,6,if(status==5)1 else 14,anchor[1].asInt(),
                poses.get(pose.toString()).map { Layer(c,it.getInt("frame"),x+it.getInt("dx"),y+it.getInt("dy")) })
        }
    }
    fun layers(party:List<Actor>)=party.sortedBy { it.anchorY }.flatMap { it.layers }
}
