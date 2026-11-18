package com.protocoss.battle

import com.badlogic.gdx.utils.JsonReader
import kotlin.math.floor

/** 원작 전투 진입 데이터. 명령·턴·종료가 연결될 때까지 일반 필드에서는 호출하지 않는다. */
class BattleEntry(json: String) {
    data class Enemy(val monster: Int, val x: Int, val y: Int, val name: String,
                     val parameterHex: String, val counter: Int)
    data class Formation(val id: Int, val background: Int, val enemies: List<Enemy>)
    private val data = JsonReader().parse(json)
    val area = data.getInt("area")
    val map = data.getInt("map")
    val width = data.getInt("width")
    val height = data.getInt("height")
    private val original = data.get("cells").asIntArray()
    val formations = data.get("formations").map { row ->
        Formation(row.getInt("id"), row.getInt("background"), row.get("enemies").map {
            Enemy(it.getInt("monster"), it.getInt("x"), it.getInt("y"), it.getString("name"),
                it.getString("parameter_hex"), it.getInt("counter"))
        })
    }.associateBy { it.id }

    init {
        require(data.getInt("version") == 1 && width > 0 && height > 0)
        require(original.size == width * height)
        require(original.all { it == 65535 || it in formations })
        require(formations.values.all { it.background in 0..31 && it.enemies.size in 1..5 })
        require(formations.values.flatMap { it.enemies }.all { it.monster in 0..122 && it.parameterHex.matches(Regex("[0-9a-f]{178}")) })
    }

    /** BAT 파일을 새로 읽은 상태. 저장 재개·맵 복귀에서의 유지 여부는 별도로 연결한다. */
    fun freshField(area: Int, map: Int): Field? =
        if (area == this.area && map == this.map) Field(original.copyOf()) else null

    inner class Field internal constructor(private val cells: IntArray) {
        fun snapshot(): IntArray = cells.copyOf()

        /** 0x13148→0x124B4가 검사하는 주인공 발밑의 셀. 좌표는 필드의 왼쪽 위 기준이다. */
        fun poll(x: Float, y: Float, busy: Boolean, enabled: Boolean, protected: Boolean): Formation? {
            require(x.isFinite() && y.isFinite())
            if (busy || !enabled || protected) return null
            val column = floor(x / 8).toInt() + 1
            val row = floor(y / 8).toInt() + 2
            if (column !in 0 until width || row !in 0 until height) return null
            val index = row * width + column
            val formation = formations[cells[index]] ?: return null
            // 원작은 화면 전환·전투 호출보다 먼저 0xFFFF를 쓴다.
            cells[index] = 65535
            return formation
        }
    }
}
