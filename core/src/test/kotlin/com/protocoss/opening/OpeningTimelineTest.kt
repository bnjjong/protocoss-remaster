package com.protocoss.opening

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OpeningTimelineTest {
    private fun opening()=OpeningTimeline.parse(Files.readString(Path.of("../assets/opening/opening.json")))
    @Test fun `original actor positions and poses match every dialogue call`() {
        val timeline=opening()
        var checkpoint=0
        repeat(20000) {
            timeline.update(1f/70f)
            if(timeline.dialogueId!=null) {
                if(timeline.pageIndex==0 && timeline.visibleCharacters==0) {
                    val expected=timeline.data.get("native_checkpoints")[checkpoint++]
                    assertEquals(expected.getInt("dialogue"),timeline.dialogueId)
                    timeline.actors.forEachIndexed { index, actor ->
                        val e=expected.get("actors")[index]
                        assertEquals(e.getFloat("x"),actor.x,"인물 $index X")
                        assertEquals(e.getFloat("y"),actor.y,"인물 $index Y")
                        assertEquals(e.getInt("frame"),actor.frame,"인물 $index 자세")
                        assertEquals(e.getInt("sprite"),actor.sprite)
                    }
                }
                timeline.advance();timeline.advance()
            }
            if(timeline.finished) { assertEquals(3,checkpoint);return }
        }
        fail("시작 연출이 끝나지 않았습니다.")
    }
    @Test fun `dialogue waits for input and freezes every actor`() {
        val timeline=opening()
        while(timeline.dialogueId==null) timeline.update(1f/70f)
        val state=timeline.actors.map { Triple(it.x,it.y,it.frame) }
        timeline.update(3600f)
        assertEquals(2,timeline.dialogueId);assertEquals(0,timeline.pageIndex)
        assertEquals(state,timeline.actors.map { Triple(it.x,it.y,it.frame) })
        timeline.advance()
        assertEquals("카만",timeline.page!!.getString("speaker"))
        timeline.advance();timeline.advance()
        assertEquals("저...\n",timeline.page!!.getString("text").take(timeline.visibleCharacters))
    }
    @Test fun `same speaker keeps the previous last line after page break`() {
        val pages=opening().data.get("dialogues").get("2")
        assertEquals("예!\n저...",pages[1].getString("text"))
        assertEquals("저...\n튜르전사 시험을 볼까 해요.",pages[2].getString("text"))
        assertEquals("벨슨그",pages[0].getString("speaker"))
    }
    @Test fun `waking and return event actors match relocated native functions`() {
        val data=opening().data
        for(program in listOf(2,3)) {
            val timeline=OpeningTimeline(data,program,program)
            val expected=data.get("field_native_checkpoints").get(program.toString())
            val seen=mutableSetOf<Int>()
            fun actors(checkpoint:com.badlogic.gdx.utils.JsonValue) {
                assertEquals(checkpoint.get("actors").size,timeline.actors.size)
                timeline.actors.forEachIndexed { i,a ->
                    val e=checkpoint.get("actors")[i]
                    assertEquals(e.getFloat("x"),a.x);assertEquals(e.getFloat("y"),a.y)
                    assertEquals(e.getInt("sprite"),a.sprite);assertEquals(e.getInt("frame"),a.frame)
                }
            }
            repeat(20000) {
                if(!timeline.finished) {
                    timeline.update(1f/70f)
                    timeline.dialogueId?.let { id ->
                        if(seen.add(id)) actors(expected.first { c -> c.getString("op")=="C" && c.getInt("dialogue")==id })
                        timeline.advance();timeline.advance()
                    }
                }
            }
            assertTrue(timeline.finished)
            assertEquals(if(program==2) setOf(17) else setOf(104,105,106),seen)
            actors(expected.last())
        }
    }
}
