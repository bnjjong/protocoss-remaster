package com.protocoss.battle

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/** 첫 파티의 단일 대상 기본 공격. 시각 상태는 원작 콜백마다 시계 한 틱을 공급한 기준이다. */
class BasicAttack(json:String,private val party:List<BattleParty.Actor>,private val formation:BattleEntry.Formation,
                  private val actorIndex:Int,private val target:Int,private val random:DosBattleRandom,
                  health:List<Int>?=null,conditions:List<Int>?=null,previousWindupFrame:Int=0) {
    data class Draw(val kind:String,val sprite:String="",val x:Int,val y:Int,val shade:Int = -1,val table:Int = -1,
                    val text:String="",val color:Int=80)
    data class Frame(val phase:String,val x:Int,val y:Int,val pose:Int,val frame:Int,val hp:List<Int>,
                     val effectFrame:Int = -1,val draws:List<Draw>)
    val data=JsonReader().parse(json)
    private val actor=requireNotNull(party.getOrNull(actorIndex))
    private fun hex(s:String)=s.chunked(2).map { it.toInt(16) }.toIntArray()
    private val p=hex(actor.parameterHex)
    private val enemyParameters=formation.enemies.map { hex(it.parameterHex) }
    private fun word(b:IntArray,i:Int)=b[i] or (b[i+1] shl 8)
    private fun int(b:IntArray,i:Int)=word(b,i) or (word(b,i+2) shl 16)
    private val weapons=listOf(p[138],p[139])
    private val rules=data.get("weapons")
    val status=(conditions ?: formation.enemies.map { 1 }).toMutableList()
    val hp=(health ?: enemyParameters.map { int(it,27) }).toMutableList()
    var luck=word(p,45);private set
    var done=false;private set
    var damage=0;private set
    val counter get()=random.counter
    val sounds=mutableListOf(0,2)
    private val definition=requireNotNull(data.get("attacks").get(actor.character.toString()))
    private val pose=definition.getInt("pose")
    private val windup=data.get("user").get(actor.character.toString()).get(pose.toString())
    private val frames=ArrayDeque<Frame>()
    private var damaged=false
    val windupEndFrame get()=windup.size-1
    val x get()=if(done)actor.x else 159
    val y get()=actor.y
    val currentPose get()=if(done)actor.pose else pose
    private fun parts(rows:JsonValue,kind:String,x:Int,y:Int)=rows.map {
        Draw("sprite","$kind-${it.getInt("frame")}",x+it.getInt("x"),y+it.getInt("y"))
    }
    init {
        require(data.getInt("version")==1 && actor.character in 0..1 && p[49]==1 && p[135] in listOf(1,3,4))
        require(target in formation.enemies.indices)
        require(hp.size==formation.enemies.size && status.size==formation.enemies.size && hp[target]>0 && status[target] !in listOf(0,5))
        require(hp.indices.all { hp[it]>0 || status[it] in listOf(0,5) })
        require(enemyParameters.all { it.size==89 })
        require(p[137] !in 102..123)
        require(weapons.all { it==255 || (it in 0..82 && rules[it].getInt("type") !in listOf(3,4) && rules[it].getInt("proc")>=87) })
        require(previousWindupFrame in 0 until windup.size)
        for(i in 0 until windup.size+1)frames.add(frame("windup",if(i==0)previousWindupFrame else i-1))
    }
    private fun frame(phase:String,index:Int=windup.size-1,effect:Int = -1,draws:List<Draw>?=null):Frame =
        Frame(phase,159,actor.y,pose,index,hp.toList(),effect,draws ?: userDraws(index))
    fun userDraws(index:Int):List<Draw> =party.sortedBy { it.anchorY }.flatMap {
        if(it===actor)parts(windup[index],"user-${it.character}",159,it.y)
        else it.layers.map { l->Draw("sprite","user-${l.character}-${l.frame}",l.x,l.y) }
    }
    private fun monsters(offset:Int=0,shade:Int = -1,table:Int = -1):List<Draw> = formation.enemies.mapIndexedNotNull { i,e ->
        if(status[i] in listOf(0,5))null else Draw("sprite","monster-${e.monster}",e.x+if(i==target)offset else 0,e.y,
            if(i==target)shade else -1,if(i==target)table else -1)
    }
    private fun resolve() {
        val e=enemyParameters[target]
        val attack=PhysicalDamage.Attacker(word(p,25),word(p,37),luck,elements=weapons.filter { it<83 }.map { rules[it].getInt("element") })
        val defender=PhysicalDamage.Defender(hp[target],int(e,59),e[72],e.slice(20..28))
        damage=PhysicalDamage.strike(attack,defender,random);hp[target]=defender.hp;luck=attack.luck
        val victim=formation.enemies[target]
        val x=victim.x+word(e,85).toShort().toInt();val y=victim.y+word(e,87).toShort().toInt()
        if(damage.toShort()>0) {
            sounds+=1
            val effect=data.get("effects").get(definition.getInt("effect").toString())
            for(i in 0 until effect.size)frames.add(frame("impact",effect=i,draws=parts(effect[i],"effect",x+definition.getInt("dx"),y+definition.getInt("dy"))))
            for(offset in data.get("shake").asIntArray())frames.add(frame("shake",draws=monsters(offset,if(offset == -2)80 else -1)))
            frames.add(frame("shake",draws=emptyList()))
        } else frames.add(frame("damage",draws=emptyList()))
        if(damage.toShort().toInt()==0) {
            sounds+=25
            val miss=data.get("effects").get("58")
            for(i in 0 until miss.size)frames.add(frame("miss",effect=i,draws=parts(miss[i],"effect",x,y+10)))
        } else {
            val offsets=data.get("float").asIntArray()
            for(i in listOf(0)+((1..19 step 2).toList()))frames.add(frame("number",draws=listOf(Draw("text",x=x,y=y+offsets[i],text=damage.toShort().toString()))))
        }
        if(hp[target]==0)repeat(5) { frames.add(frame("defeat",draws=monsters(table=2))) }
    }
    /** 한 번에 원작 콜백 한 시점만 진행한다. 피해 추첨은 준비 동작을 마친 뒤에 실행한다. */
    fun next():Frame? {
        if(done)return null
        if(frames.isEmpty() && !damaged) { resolve();damaged=true }
        if(frames.isNotEmpty())return frames.removeFirst()
        if(hp[target]==0)status[target]=0
        // 추가 주문이 없는 무기도 원작의 발동 확률 추첨은 소비한다.
        val alive=status.count { it !in listOf(0,5) }
        for(weapon in weapons)if(weapon<83) { if(alive==0)break;random.next(10) }
        done=true
        return null
    }
}
