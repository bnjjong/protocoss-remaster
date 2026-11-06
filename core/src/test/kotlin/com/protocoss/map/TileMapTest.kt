package com.protocoss.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TileMap]의 좌표 변환, 타일 인덱싱 및 경계 판정 로직을 검증하는 단위 테스트입니다.
 */
class TileMapTest {

    @Test
    fun `tile coordinates and world pixel conversions work properly`() {
        val map = TileMap(width = 20, height = 15, tileWidth = 16, tileHeight = 16)

        assertEquals(320f, map.totalPixelWidth)
        assertEquals(240f, map.totalPixelHeight)

        assertEquals(0, map.worldToTileX(0f))
        assertEquals(1, map.worldToTileX(16f))
        assertEquals(1, map.worldToTileX(31.9f))
        assertEquals(2, map.worldToTileX(32f))

        assertEquals(0, map.worldToTileY(0f))
        assertEquals(5, map.worldToTileY(80f))
    }

    @Test
    fun `get and set tile updates grid correctly`() {
        val map = TileMap(width = 10, height = 10)
        assertEquals(0, map.getTile(3, 4))

        map.setTile(3, 4, 42)
        assertEquals(42, map.getTile(3, 4))

        // Out of bounds returns 0
        assertEquals(0, map.getTile(-1, 0))
        assertEquals(0, map.getTile(10, 10))
    }

    @Test
    fun `bounds checking detects valid world positions`() {
        val map = TileMap(width = 10, height = 10, tileWidth = 16, tileHeight = 16)
        assertTrue(map.isInBounds(0f, 0f))
        assertTrue(map.isInBounds(160f, 160f))
        assertFalse(map.isInBounds(-1f, 50f))
        assertFalse(map.isInBounds(50f, 161f))
    }
}
