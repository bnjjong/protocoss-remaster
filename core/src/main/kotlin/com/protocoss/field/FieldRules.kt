package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import kotlin.math.floor

/** 원작 0x23154가 판정한 통과 방향·높이 층. 실행 때 DOS 파일이나 에뮬레이터가 필요 없다. */
class FieldRules(json: String, read: (String) -> ByteArray) {
    private class MapData(val source: JsonValue, val movement: ByteArray) {
        val width = source.getInt("width")
        val height = source.getInt("height")
        init { require(movement.size == width * height * 12) }
    }
    val data = JsonReader().parse(json)
    private val maps = data.get("maps").associate { it.name to MapData(it, read(it.getString("file"))) }
    data class Step(val direction: Int, val layer: Int)
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
        val m = maps.getValue(if(map==0 && doorOpen) "0-open" else map.toString())
        var column = floor(x / 8f).toInt() + 1
        var row = floor(y / 8f).toInt() + 2
        when (direction) { 1 -> row--; 2 -> column += 2; 4 -> row++; 8 -> column -= 2 }
        if (column !in 0 until m.width || row !in 0 until m.height) return null
        if (m.source.get("attributes")[row * m.width + column].asInt() and 8 == 0) return null
        return m.source.get("rectangles").firstOrNull {
            column in it[0].asInt()..it[2].asInt() && row in it[1].asInt()..it[3].asInt()
        }?.get(4)?.asInt()
    }
    companion object {
        fun dx(direction: Int) = when (direction) { 2, 3, 6 -> 1; 8, 9, 12 -> -1; else -> 0 }
        fun dy(direction: Int) = when (direction) { 1, 3, 9 -> -1; 4, 6, 12 -> 1; else -> 0 }
    }
}
