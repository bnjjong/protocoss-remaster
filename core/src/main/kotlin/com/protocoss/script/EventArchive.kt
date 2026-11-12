package com.protocoss.script

/** 전 지역 G&M의 그룹·인자 진입 주소와 원본 명령 바이트를 보존한다. */
class EventArchive(raw:ByteArray) {
    data class Route(val argument:Int,val destination:Int)
    data class Group(val offset:Int,val routes:List<Route>,val fallback:Int)
    private val code:ByteArray
    val groups:List<Group>
    val codeSize get()=code.size

    init {
        require(raw.size>=6) { "이벤트 파일 헤더가 잘렸습니다." }
        val length=(0..3).fold(0L) { value,i -> value or ((raw[i].toLong() and 255) shl (i*8)) }
        require(length==raw.size-6L) { "이벤트 파일 길이가 헤더와 다릅니다." }
        fun rawWord(p:Int):Int {
            require(p>=0 && p+2<=raw.size) { "이벤트 그룹 목록이 잘렸습니다." }
            return (raw[p].toInt() and 255) or ((raw[p+1].toInt() and 255) shl 8)
        }
        val count=rawWord(4);val start=6+count*2
        require(count>0 && start<raw.size) { "이벤트 그룹 목록이 잘못되었습니다." }
        code=ByteArray(raw.size-start) { (raw[start+it].toInt() xor 127).toByte() }
        require(code.size<=65536) { "16비트 이벤트 주소 범위를 넘었습니다." }
        groups=List(count) { number ->
            val offset=rawWord(6+number*2);var p=offset
            val routes=mutableListOf<Route>()
            while(byte(p)==0xf0) {
                require(word(p+1)==1) { "분석한 이벤트 인자 분기 형식과 다릅니다." }
                val argument=word(p+3);val destination=word(p+5)
                byte(destination);routes+=Route(argument,destination);p+=7
            }
            require(byte(p)==0xfa) { "이벤트 기본 분기의 점프 명령이 다릅니다." }
            val fallback=word(p+1);byte(fallback)
            Group(offset,routes.toList(),fallback)
        }
    }

    fun byte(offset:Int):Int {
        require(offset in code.indices) { "이벤트 주소가 파일 범위를 벗어났습니다." }
        return code[offset].toInt() and 255
    }
    internal fun word(offset:Int)=byte(offset) or (byte(offset+1) shl 8)

    fun resolve(group:Int,argument:Int):Int {
        require(group in groups.indices && argument in 0..65535) { "이벤트 그룹·인자 범위 오류" }
        val entry=groups[group]
        return entry.routes.firstOrNull { it.argument==argument }?.destination ?: entry.fallback
    }
}
