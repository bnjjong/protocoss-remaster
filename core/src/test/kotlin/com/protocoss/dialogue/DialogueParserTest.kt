package com.protocoss.dialogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogueParserTest {

    @Test
    fun `parseSpeakerTag correctly extracts portraitId and speakerName`() {
        val (p1, n1) = DialogueParser.parseSpeakerTag("5S0카만")
        assertEquals(0, p1)
        assertEquals("카만", n1)

        val (p2, n2) = DialogueParser.parseSpeakerTag("4S17벨슨그")
        assertEquals(17, p2)
        assertEquals("벨슨", n2)

        val (p3, n3) = DialogueParser.parseSpeakerTag("2S1엔키드")
        assertEquals(1, p3)
        assertEquals("엔키드", n3)

        val (p4, n4) = DialogueParser.parseSpeakerTag("")
        assertEquals(0, p4)
        assertEquals("", n4)
    }

    @Test
    fun `parsePages correctly splits text by delimiter`() {
        val raw = "첫 번째 페이지입니다.\n--- (다음) ---\n두 번째 페이지입니다.\n--- (다음) ---\n세 번째 페이지입니다."
        val pages = DialogueParser.parsePages("5S0카만", raw)

        assertEquals(3, pages.size)
        assertEquals("카만", pages[0].speakerName)
        assertEquals("첫 번째 페이지입니다.", pages[0].text)
        assertEquals("두 번째 페이지입니다.", pages[1].text)
        assertEquals("세 번째 페이지입니다.", pages[2].text)
    }
}
