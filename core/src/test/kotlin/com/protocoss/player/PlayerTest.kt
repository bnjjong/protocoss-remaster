package com.protocoss.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Player] 엔티티의 이동, 대시 및 상태 전환을 검증하는 단위 테스트입니다.
 */
class PlayerTest {

    @Test
    fun `player moves north with walk speed`() {
        val player = Player(initialX = 100f, initialY = 100f)
        assertEquals(PlayerState.IDLE, player.state)
        assertFalse(player.isMoving)

        // 1초간 위로 이동
        player.update(delta = 1.0f, up = true, down = false, left = false, right = false)

        assertEquals(PlayerState.WALKING, player.state)
        assertTrue(player.isMoving)
        assertEquals(Direction.NORTH, player.direction)
        assertEquals(100f, player.x)
        assertEquals(100f + Player.WALK_SPEED, player.y)
    }

    @Test
    fun `player dashes faster than walk speed`() {
        val player = Player(initialX = 100f, initialY = 100f)

        // 1초간 대시로 오른쪽 이동
        player.update(delta = 1.0f, up = false, down = false, left = false, right = true, isDash = true)

        assertEquals(PlayerState.WALKING, player.state)
        assertEquals(Direction.EAST, player.direction)
        assertEquals(100f + Player.DASH_SPEED, player.x)
        assertEquals(100f, player.y)
    }

    @Test
    fun `player stops when input is released`() {
        val player = Player(initialX = 100f, initialY = 100f)
        player.update(delta = 0.5f, up = true, down = false, left = false, right = false)
        assertTrue(player.isMoving)

        // 키를 뗐을 때
        player.update(delta = 0.1f, up = false, down = false, left = false, right = false)
        assertEquals(PlayerState.IDLE, player.state)
        assertFalse(player.isMoving)
        // 마지막 바라본 방향은 유지
        assertEquals(Direction.NORTH, player.direction)
    }
}
