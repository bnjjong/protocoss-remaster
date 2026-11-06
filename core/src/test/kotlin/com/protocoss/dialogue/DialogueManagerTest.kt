package com.protocoss.dialogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueManagerTest {

    @Test
    fun `dialogue starts, types characters, and advances through pages`() {
        val manager = DialogueManager()
        var ended = false
        manager.onDialogueEnd = { ended = true }

        val page1 = DialoguePage("카만", "안녕", 0)
        val page2 = DialoguePage("벨슨", "반갑구나", 17)
        val segment = DialogueSegment(
            segmentId = 0,
            dialogues = listOf(
                DialogueLine("5S0카만", "안녕", listOf(page1)),
                DialogueLine("4S17벨슨", "반갑구나", listOf(page2))
            )
        )

        manager.startDialogue(segment)
        assertTrue(manager.isActive)
        assertEquals(0, manager.currentPageIndex)
        assertEquals("카만", manager.currentPage?.speakerName)

        // 타이핑 진행 전: 0자
        assertEquals("", manager.visibleText)
        assertFalse(manager.isPageComplete)

        // advance() 호출 시 타이핑 중이면 즉시 전체 글자 표시
        val continued = manager.advance()
        assertTrue(continued)
        assertEquals("안녕", manager.visibleText)
        assertTrue(manager.isPageComplete)

        // 완료 상태에서 advance() 호출 시 다음 페이지로 전환
        val continued2 = manager.advance()
        assertTrue(continued2)
        assertEquals(1, manager.currentPageIndex)
        assertEquals("벨슨", manager.currentPage?.speakerName)

        // 마지막 페이지 전체 표시 후 advance() 시 대화 종료
        manager.advance() // 즉시 표시
        val continued3 = manager.advance() // 대화 종료
        assertFalse(continued3)
        assertFalse(manager.isActive)
        assertTrue(ended)
    }
}
