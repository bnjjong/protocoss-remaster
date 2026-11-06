package com.protocoss.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 8방향 벡터 및 키 조합 판정 로직을 검증하는 단위 테스트입니다.
 */
class DirectionTest {

    @Test
    fun `single direction input maps correctly`() {
        assertEquals(Direction.NORTH, Direction.fromInput(up = true, down = false, left = false, right = false))
        assertEquals(Direction.SOUTH, Direction.fromInput(up = false, down = true, left = false, right = false))
        assertEquals(Direction.WEST, Direction.fromInput(up = false, down = false, left = true, right = false))
        assertEquals(Direction.EAST, Direction.fromInput(up = false, down = false, left = false, right = true))
    }

    @Test
    fun `diagonal direction input maps correctly`() {
        assertEquals(Direction.NORTH_EAST, Direction.fromInput(up = true, down = false, left = false, right = true))
        assertEquals(Direction.NORTH_WEST, Direction.fromInput(up = true, down = false, left = true, right = false))
        assertEquals(Direction.SOUTH_EAST, Direction.fromInput(up = false, down = true, left = false, right = true))
        assertEquals(Direction.SOUTH_WEST, Direction.fromInput(up = false, down = true, left = true, right = false))
    }

    @Test
    fun `conflicting or empty inputs result in null`() {
        // No input
        assertNull(Direction.fromInput(up = false, down = false, left = false, right = false))
        // Up + Down cancels out
        assertNull(Direction.fromInput(up = true, down = true, left = false, right = false))
        // Left + Right cancels out
        assertNull(Direction.fromInput(up = false, down = false, left = true, right = true))
        // All four pressed cancels out
        assertNull(Direction.fromInput(up = true, down = true, left = true, right = true))
    }
}
