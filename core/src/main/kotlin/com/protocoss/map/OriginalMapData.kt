package com.protocoss.map

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** DOS 저장 파일이 가리키는 맵을 원본 MAP/SOC에서 직접 읽는다. */
data class OriginalMapData(val map: TileMap, val rgba: ByteArray, val atlasWidth: Int = 320, val atlasHeight: Int = 208) {
    companion object {
        fun load(gameDirectory: Path, area: Int, index: Int): OriginalMapData {
            require(area in 0..5)
            val prefix = gameDirectory.resolve("MAP/AREA%02d".format(area)).toString()
            val offsets = offsets(Files.readAllBytes(Path.of("$prefix.MDA")))
            require(index in offsets.indices) { "저장 파일의 맵 번호가 원본 목록에 없습니다." }
            val allMaps = Files.readAllBytes(Path.of("$prefix.MAP"))
            val end = offsets.getOrNull(index + 1) ?: allMaps.size
            require(offsets[index] >= 0 && end <= allMaps.size && end - offsets[index] >= 48)
            val source = allMaps.copyOfRange(offsets[index], end)
            val width = (source[2].toInt() and 255) * 40
            val height = (source[3].toInt() and 255) * 25
            require(width > 0 && height > 0 && 48L + width.toLong() * height * 4 <= source.size) { "맵 데이터가 잘렸습니다." }
            // 파일명 뒤에 남은 점·문자열 대신 원작 0x1E6D6이 쓰는 헤더의 타일셋 번호를 읽는다.
            val block = source[32].toInt() and 255
            val socOffsets = offsets(Files.readAllBytes(Path.of("$prefix.SDA")))
            require(block in socOffsets.indices)
            val soc = Files.readAllBytes(Path.of("$prefix.SOC"))
            val start = socOffsets[block]
            val socEnd = socOffsets.getOrNull(block + 1) ?: soc.size
            require(start >= 0 && socEnd <= soc.size && socEnd - start == 70818) { "SOC 블록 크기가 다릅니다." }
            require((start + 2 until start + 770).all { (soc[it].toInt() and 255) <= 63 })
            val rgba = ByteArray(320 * 208 * 4)
            for (tile in 0 until 1000) for (y in 0 until 8) for (x in 0 until 8) {
                val color = soc[start + 770 + tile * 64 + x % 4 * 16 + y * 2 + x / 4].toInt() and 255
                val slot = tile + 1
                val pixel = ((slot / 40 * 8 + y) * 320 + slot % 40 * 8 + x) * 4
                for (channel in 0..2) rgba[pixel + channel] = (soc[start + 2 + color * 3 + channel] * 4).toByte()
                rgba[pixel + 3] = 255.toByte()
            }
            val map = TileMap(width, height, 8, 8)
            val buffer = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until height) for (x in 0 until width) {
                val tile = buffer.getShort(48 + (y * width + x) * 4).toInt() and 0xfff
                require(tile < 1000) { "원본 타일 번호가 범위를 벗어났습니다." }
                map.setTile(x, height - 1 - y, tile + 1)
            }
            return OriginalMapData(map, rgba)
        }

        private fun offsets(bytes: ByteArray): IntArray {
            require(bytes.size >= 4)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            require(count in 1..1024 && bytes.size == 4 + count * 4)
            val values = IntArray(count) { buffer.int }
            require(values[0] == 0 && (1 until count).all { values[it - 1] < values[it] })
            return values
        }
    }
}
