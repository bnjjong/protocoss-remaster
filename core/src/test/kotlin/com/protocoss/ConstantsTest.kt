package com.protocoss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Constants]의 수치 및 픽셀 스케일 계산 규칙을 검증하는 단위 테스트입니다.
 */
class ConstantsTest {

    @Test
    fun `verify aspect ratio and scaling calculations`() {
        assertEquals(320, Constants.ORIGINAL_WIDTH)
        assertEquals(200, Constants.ORIGINAL_HEIGHT)
        assertEquals(4, Constants.DEFAULT_WINDOW_SCALE)
        assertEquals(1280, Constants.WINDOW_WIDTH)
        assertEquals(800, Constants.WINDOW_HEIGHT)
        assertTrue(Constants.GAME_TITLE.isNotBlank())
    }
}
