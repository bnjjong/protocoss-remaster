package com.protocoss.dialogue

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DialogueRepositoryTest {

    @Test
    fun `loadFromJson parses real script file correctly`() {
        val jsonFile = File("assets/data/scripts/area00_scripts.json")
        val repository = DialogueRepository()

        if (jsonFile.exists()) {
            repository.loadFromJson(jsonFile.readText(Charsets.UTF_8))
            assertEquals(210, repository.size)

            val seg0 = repository.getSegment(0)
            assertNotNull(seg0)
            assertEquals(0, seg0.segmentId)
            assertEquals(5, seg0.dialogues.size)

            val firstPage = seg0.allPages.first()
            assertEquals("벨슨", firstPage.speakerName)
            assertEquals(17, firstPage.portraitId)
        } else {
            // 폴백 인라인 JSON 테스트
            val sampleJson = """
            {
              "file": "AREA00.G&M",
              "total_segments": 1,
              "segments": [
                {
                  "segment_id": 0,
                  "dialogues": [
                    {
                      "speaker_tag": "5S0카만",
                      "text": "안녕하세요!\n--- (다음) ---\n반갑습니다."
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
            repository.loadFromJson(sampleJson)
            assertEquals(1, repository.size)
            val seg = repository.getSegment(0)
            assertNotNull(seg)
            assertEquals(2, seg.allPages.size)
        }
    }
}
