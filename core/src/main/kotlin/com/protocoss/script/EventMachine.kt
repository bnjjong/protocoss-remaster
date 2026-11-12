package com.protocoss.script

/** 원작의 16비트 이벤트 변수·분기·호출을 실행한다. 화면·음악 명령은 별도로 연결한다. */
class EventMachine(private val archive:EventArchive, initialVariables:IntArray, argument:Int,private val fieldServices:Boolean=false) {
    sealed interface Stop {
        data class Finished(val reason:Reason):Stop
        data class Budget(val offset:Int):Stop
        data class Unhandled(val offset:Int,val opcode:Int):Stop
        data class Warp internal constructor(val offset:Int,val map:Int,val scrollX:Int,val scrollY:Int,
            val x:Int,val y:Int,val pose:Int,val layer:Int,val music:Int,internal val nextOffset:Int):Stop
        data class Reward internal constructor(val offset:Int,val item:Int,val count:Int,val category:Int,internal val nextOffset:Int):Stop
        data class Panel internal constructor(val offset:Int,val opening:Boolean,internal val nextOffset:Int):Stop
        data class Door internal constructor(val offset:Int,val id:Int,internal val nextOffset:Int):Stop
        data class Effect internal constructor(val offset:Int,val id:Int,internal val nextOffset:Int):Stop
        class Place internal constructor(val offset:Int,private val encoded:ByteArray,internal val nextOffset:Int):Stop {
            fun bytes()=encoded.copyOf()
        }
        class Dialogue internal constructor(val offset:Int,private val encoded:ByteArray,internal val nextOffset:Int):Stop {
            fun bytes()=encoded.copyOf()
        }
    }
    enum class Reason { RETURN, CALL_LIMIT }
    class Fault(val offset:Int,val opcode:Int,reason:String):IllegalStateException(
        "이벤트 ${offset.toString(16)} 명령 ${opcode.toString(16)}: $reason")

    private val values=initialVariables.copyOf()
    private val returns=mutableListOf<Int>()
    private var stopped:Stop?=null
    val pending:Stop? get()=stopped
    var offset:Int;private set
    var executedInstructions=0L;private set

    init {
        require(values.size==300 && values.all { it in 0..65535 }) { "이벤트 변수 300개의 16비트 값이 필요합니다." }
        require(values[0] in archive.groups.indices && argument in 0..65535) { "이벤트 그룹·인자 범위 오류" }
        values[1]=argument
        offset=archive.groups[values[0]].offset
    }

    fun variables()=values.copyOf()
    fun returnAddresses()=returns.toList()
    fun setVariable(index:Int,value:Int) {
        require(index in values.indices && value in 0..65535) { "이벤트 변수 범위 오류" }
        values[index]=value
    }

    fun resumeDialogue() {
        val request=stopped as? Stop.Dialogue ?: error("대화 대기 중이 아닙니다.")
        offset=request.nextOffset;stopped=null
    }

    fun resumeDoor() { check(stopped is Stop.Door) { "문 명령 대기 중이 아닙니다." };resumeService() }

    fun resumeService() {
        offset=when(val request=stopped) {
            is Stop.Warp -> { values[4]=request.music;request.nextOffset }
            is Stop.Reward -> request.nextOffset
            is Stop.Panel -> request.nextOffset
            is Stop.Door -> request.nextOffset
            is Stop.Effect -> request.nextOffset
            is Stop.Place -> request.nextOffset
            else -> error("필드 명령 대기 중이 아닙니다.")
        }
        stopped=null
    }

    /** 대기 중 다시 호출해도 대화나 미지원 명령을 넘기지 않는다. 예산 소진 후에는 이어서 실행한다. */
    fun run(maxInstructions:Int=10000):Stop {
        require(maxInstructions>0)
        stopped?.let { return it }
        repeat(maxInstructions) {
            val start=offset
            val opcode=try { archive.byte(start) } catch(e:IllegalArgumentException) {
                throw Fault(start,-1,e.message ?: "명령 주소 오류")
            }
            var cursor=start+1
            fun word():Int = archive.word(cursor).also { cursor+=2 }
            fun index(value:Int):Int = value.also { require(it in values.indices) { "분석한 변수 300개 범위를 벗어났습니다." } }
            fun operand(value:Int):Int = if(value and 0x8000!=0) values[index(value and 0x7fff)] else value
            fun target(value:Int):Int = value.also { archive.byte(it) }
            try {
                when(opcode) {
                    2,0xa,0x11,0x15,0x17,0x18,0x19 -> {
                        if(!fieldServices) { stopped=Stop.Unhandled(start,opcode);return stopped!! }
                        when(opcode) {
                            2 -> {
                                val args=List(8) { word() }
                                stopped=Stop.Warp(start,args[0],args[1],args[2],args[3],args[4],args[5],args[6],args[7],cursor)
                            }
                            0x11 -> { val item=word();val count=word();val category=word();stopped=Stop.Reward(start,item,count,category,cursor) }
                            0x18,0x19 -> stopped=Stop.Panel(start,opcode==0x19,cursor)
                            0xa -> { val id=word();stopped=Stop.Door(start,id,cursor) }
                            0x15 -> { val id=word();stopped=Stop.Effect(start,id,cursor) }
                            else -> {
                                val bytes=mutableListOf<Byte>()
                                while(archive.byte(cursor)!=0) { bytes+=archive.byte(cursor++).toByte() }
                                cursor++;stopped=Stop.Place(start,bytes.toByteArray(),cursor)
                            }
                        }
                    }
                    0xf0 -> {
                        val variable=index(word());val value=word();val destination=word()
                        if(values[variable]==value) cursor=target(destination)
                    }
                    0xf1 -> {
                        if(returns.isEmpty()) stopped=Stop.Finished(Reason.RETURN)
                        else { cursor=target(returns.last());returns.removeAt(returns.lastIndex) }
                    }
                    0xf2 -> {
                        val destination=index(word() and 0x7fff);val value=operand(word())
                        values[destination]=value
                    }
                    in 0xf3..0xf7 -> {
                        val destination=index(word() and 0x7fff);val left=operand(word());val right=operand(word())
                        require(opcode !in 0xf3..0xf4 || right!=0) { "0으로 나눌 수 없습니다." }
                        values[destination]=when(opcode) {
                            0xf3 -> left%right
                            0xf4 -> left/right
                            0xf5 -> left*right
                            0xf6 -> left-right
                            else -> left+right
                        } and 65535
                    }
                    0xf8 -> {
                        // 원작은 그룹 수와 같은 깊이가 되면 인자를 읽기 전에 호출 전체를 끝낸다.
                        if(returns.size+1==archive.groups.size) {
                            returns.clear();stopped=Stop.Finished(Reason.CALL_LIMIT)
                        } else {
                            // 원작의 물리 저장소 10개를 넘는 메모리 침범은 실행하지 않는다.
                            require(returns.size<10) { "원작 복귀 주소 저장소의 분석 범위를 넘었습니다." }
                            val destination=target(word())
                            returns+=(cursor and 65535);cursor=destination
                        }
                    }
                    0xf9 -> {
                        val left=operand(word());val comparison=archive.byte(cursor++);val right=operand(word())
                        val condition=when(comparison) {
                            0 -> left<right
                            1 -> left<=right
                            2 -> left>right
                            3 -> left>=right
                            4 -> left!=right
                            5 -> left==right
                            else -> throw IllegalArgumentException("미지원 비교 연산: $comparison")
                        }
                        if(condition) cursor=target(cursor+3)
                    }
                    0xfa -> cursor=target(word())
                    3 -> {
                        val bytes=mutableListOf<Byte>()
                        while(archive.byte(cursor)!=0) { bytes+=archive.byte(cursor).toByte();cursor++ }
                        stopped=Stop.Dialogue(start,bytes.toByteArray(),cursor+1)
                        cursor=start+1
                    }
                    else -> {
                        stopped=Stop.Unhandled(start,opcode)
                        return stopped!!
                    }
                }
            } catch(e:IllegalArgumentException) { throw Fault(start,opcode,e.message ?: "명령 인자 오류") }
            offset=cursor;executedInstructions++
            stopped?.let { return it }
        }
        return Stop.Budget(offset)
    }
}
