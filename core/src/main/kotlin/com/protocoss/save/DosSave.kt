package com.protocoss.save

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.time.LocalTime
import kotlin.math.roundToInt

/** 원작 0x1BB74가 읽는 2,944바이트. 아직 사용하지 않는 상태도 그대로 보존한다. */
class DosSave private constructor(private val data: ByteArray) {
    private fun int(offset: Int) = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
    val location: String get() = String(data.copyOfRange(0, 80).takeWhile { it != 0.toByte() }.toByteArray(), JOHAB)
    val area: Int get() = int(92)
    val map: Int get() = int(96)
    val scrollX: Int get() = int(100)
    val scrollY: Int get() = int(104)
    val screenX: Int get() = int(108)
    val screenY: Int get() = int(112)
    val worldX: Float get() = scrollX * 8f + screenX - 16f
    fun worldY(mapHeight: Float): Float = mapHeight - (scrollY * 8f + screenY - 16f)
    fun bytes(): ByteArray = data.copyOf()

    /** 좌표 외의 인벤토리·파티·이벤트 600바이트 등을 다시 쓰거나 초기화하지 않는다. */
    fun atPosition(x: Float, y: Float, mapHeight: Float, mapWidth: Float = 320f): DosSave {
        require(x.isFinite() && y.isFinite())
        val result = data.copyOf()
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        val topY = mapHeight - y
        val sx = if (x - scrollX * 8f in 0f..320f) scrollX
            else ((x - 160f) / 8f).toInt().coerceIn(0, ((mapWidth - 320f) / 8f).toInt().coerceAtLeast(0))
        val sy = if (topY - scrollY * 8f in 0f..200f) scrollY
            else ((topY - 100f) / 8f).toInt().coerceIn(0, ((mapHeight - 200f) / 8f).toInt().coerceAtLeast(0))
        buffer.putInt(100, sx)
        buffer.putInt(104, sy)
        buffer.putInt(108, (x - sx * 8f + 16f).roundToInt())
        buffer.putInt(112, (topY - sy * 8f + 16f).roundToInt())
        return parse(result)
    }

    /** 원작은 마지막 uint16과 DOS 수정 시각의 XOR을 비교한다. */
    fun forTime(time: LocalTime): ByteArray {
        val result = data.copyOf()
        val dosTime = (time.hour shl 11) or (time.minute shl 5) or (time.second / 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putShort(2942, (dosTime xor 0x7f7f).toShort())
        return result
    }

    companion object {
        const val SIZE = 2944
        private val JOHAB = Charset.forName("x-Johab")
        fun parse(bytes: ByteArray): DosSave {
            require(bytes.size == SIZE) { "DOS 저장 파일 크기가 올바르지 않습니다." }
            val save = DosSave(bytes.copyOf())
            require(save.area in 0..5 && save.map in 0..1023) { "지원하지 않는 지역·맵 번호입니다." }
            require(save.scrollX in 0..10000 && save.scrollY in 0..10000 &&
                save.screenX in -10000..10000 && save.screenY in -10000..10000) { "저장 좌표가 올바르지 않습니다." }
            return save
        }

        /** AREA00.G&M 초기 분기의 0x16 명령: 맵 2, 스크롤 0,0, 화면 위치 96,56. */
        fun newGame(start: ByteArray): DosSave {
            val result = parse(start).bytes()
            ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(96, 2)
                putInt(100, 0)
                putInt(104, 0)
                putInt(108, 96)
                putInt(112, 56)
            }
            return parse(result)
        }
    }
}
