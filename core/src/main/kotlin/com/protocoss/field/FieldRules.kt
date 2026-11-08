package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import kotlin.math.floor

/** 원작 0x23154가 판정한 통과 방향·높이 층. 실행 때 DOS 파일이나 에뮬레이터가 필요 없다. */
class FieldRules(json: String, read: (String) -> ByteArray) {
    private class MapData(val source: JsonValue, val originalMovement: ByteArray) {
        val width = source.getInt("width")
        val height = source.getInt("height")
        var movement=originalMovement.copyOf()
        val attributes=source.get("attributes").asIntArray()
        val opened=mutableSetOf<Int>()
        init { require(movement.size == width * height * 12) }
    }
    val data = JsonReader().parse(json)
    private val maps = data.get("maps").associate { it.name to MapData(it, read(it.getString("file"))) }
    data class Step(val direction: Int, val layer: Int)

    fun reset(map:Int) {
        val m=maps[map.toString()] ?: return
        m.originalMovement.copyInto(m.movement)
        m.source.get("attributes").asIntArray().copyInto(m.attributes)
        m.opened.clear()
    }

    /** 문 하나의 원작 타일 변경과 이동 판정만 적용한다. 다른 문은 그대로 둔다. */
    fun openDoor(map:Int,x:Float,y:Float,direction:Int):JsonValue? {
        val m=maps[map.toString()] ?: return null
        val doors=m.source.get("doors") ?: return null
        val selected=doors.indexOfFirst { it.getInt("x")==floor(x/8).toInt()*8 && it.getInt("y")==floor(y/8).toInt()*8 && it.getInt("direction")==direction }
        if(selected<0 || !m.opened.add(selected)) return null
        val door=doors[selected]
        door.get("movement").forEach { m.movement[it[0].asInt()]=it[1].asInt().toByte() }
        door.get("changes").forEach { m.attributes[it.getInt("index")]=it.getInt("attr") }
        return door.get("changes")
    }
    fun step(map: Int, x: Float, y: Float, direction: Int, layer: Int, doorOpen: Boolean = false): Step {
        val m = maps.getValue(if(map==0 && doorOpen) "0-open" else map.toString())
        val column = floor(x / 8f).toInt(); val row = floor(y / 8f).toInt()
        val index = listOf(1, 2, 4, 8).indexOf(direction)
        require(index >= 0 && layer in 0..2)
        if (column !in 0 until m.width || row !in 0 until m.height) return Step(0, layer)
        val value = m.movement[((layer * m.height + row) * m.width + column) * 4 + index].toInt() and 255
        val result = value and 15
        val targetX = x + dx(result) * 8; val targetY = y + dy(result) * 8
        if (targetX < 0 || targetY < 0 || targetX + 24 > m.width * 8 || targetY + 24 > m.height * 8) return Step(0, layer)
        return Step(result, value shr 4)
    }

    /** 충돌보다 먼저 검사하는 원작 0x20C10의 발 앞쪽 이벤트 타일. */
    fun event(map: Int, x: Float, y: Float, direction: Int, doorOpen: Boolean = false): Int? {
        return frontEvent(map,x,y,direction,doorOpen,8)
    }
    /** 원작 0x1204C: 이동 이벤트와 별개인 조작 키용 타일 속성 4. */
    fun interaction(map:Int,x:Float,y:Float,pose:Int,doorOpen:Boolean=false):Int? =
        frontEvent(map,x,y,when(pose/4) { 0->1;1->2;2->4;else->8 },doorOpen,4)

    private fun frontEvent(map:Int,x:Float,y:Float,direction:Int,doorOpen:Boolean,flag:Int):Int? {
        val m = maps.getValue(if(map==0 && doorOpen) "0-open" else map.toString())
        var column = floor(x / 8f).toInt() + 1
        var row = floor(y / 8f).toInt() + 2
        when (direction) { 1 -> row--; 2 -> column += 2; 4 -> row++; 8 -> column -= 2 }
        if (column !in 0 until m.width || row !in 0 until m.height) return null
        if (m.attributes[row * m.width + column] and flag == 0) return null
        return m.source.get("rectangles").firstOrNull {
            column in it[0].asInt()..it[2].asInt() && row in it[1].asInt()..it[3].asInt()
        }?.get(4)?.asInt()
    }
    companion object {
        fun dx(direction: Int) = when (direction) { 2, 3, 6 -> 1; 8, 9, 12 -> -1; else -> 0 }
        fun dy(direction: Int) = when (direction) { 1, 3, 9 -> -1; 4, 6, 12 -> 1; else -> 0 }
    }
}
