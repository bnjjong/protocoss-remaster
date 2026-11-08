package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import com.protocoss.opening.OpeningTimeline
import kotlin.test.*

class SwordStoryTest {
    private val data=JsonReader().parse(Files.readString(Path.of("../assets/opening/opening.json")))
    private val story=SwordStory(data.get("sword_story"))
    private val report=JsonReader().parse(Files.readString(Path.of("../docs/sword-native-verification.json")))
    @Test fun `native full save comparisons include overflow healing party forms and accessories`() {
        for(case in report.get("cases")) {
            val before=DosSave.parse(Base64.getDecoder().decode(case.getString("before")))
            val expected=Base64.getDecoder().decode(case.getString("after"))
            val after=when(case.getInt("group")) {
                4 -> story.finishShop(if(before.variable(8)==3) before.receiveRepairedSword() else before)
                3 -> story.townTriggerState(before)
                else -> assertNotNull(CabinDoors.travel(before,1))
            }.bytes()
            for(i in expected.indices) {
                if(case.getInt("group")==1 && i in 96..118) continue // 이동 인자는 별도 원작 분기 검사에서 대조한다.
                assertEquals(expected[i],after[i],"그룹 ${case.getInt("group")}, 단계 ${case.getInt("stage")}, 표본 ${case.getInt("variant")}, 저장 $i")
            }
        }
    }
    @Test fun `repair keeps equipment while returned sword is inventory only`() {
        val before=DosSave.afterOpening(Files.readAllBytes(Path.of("../assets/data/original/start.dat")))
            .travel(5,0,0,152,176,0,0,mapOf(0 to 4,8 to 0,99 to 456))
        val stored=story.finishShop(before)
        assertEquals(1,stored.variable(8));assertEquals(before.bytes()[473],stored.bytes()[473])
        val ready=stored.withVariables(mapOf(8 to 3))
        val collected=story.finishShop(ready.receiveRepairedSword())
        assertEquals(1,collected.bytes()[162].toInt());assertEquals(4,collected.variable(8))
        assertEquals(ready.bytes()[473],collected.bytes()[473]);assertEquals(456,collected.variable(99))
        val repeated=story.finishShop(collected)
        assertEquals(1,repeated.bytes()[162].toInt());assertEquals(4,repeated.variable(8))
    }
    @Test fun `shop source text and reward page match native branch`() {
        for(case in report.get("cases").filter { it.getInt("group")==4 && it.getInt("variant")==0 }) {
            val before=DosSave.parse(Base64.getDecoder().decode(case.getString("before")))
            val pages=assertNotNull(story.shopPages(before,2))
            val text=pages.map { it.getString("text") }.joinToString(" ").replace("\n","")
            for(event in case.get("events").filter { it.getString("kind")=="dialogue" })
                for(chunk in event.getString("text").split(Regex("/\\[[^]]+]|/[NDE]"))) assertTrue(text.contains(chunk),chunk)
            assertEquals(if(case.getInt("stage")==3) 1 else 0,pages.count { it.getString("kind","")=="reward" })
        }
    }
    @Test fun `join waits for correct trigger and takes native final pose and scroll`() {
        val before=DosSave.afterOpening(Files.readAllBytes(Path.of("../assets/data/original/start.dat")))
            .travel(4,20,7,160,104,8,0,mapOf(0 to 3,8 to 1,4 to 24))
        assertTrue(story.joins(before,15));assertFalse(story.joins(before,2))
        val joined=story.joinDestination(before)
        assertEquals(listOf(4,14,7,160,104,8,0,24,2,17),listOf(joined.map,joined.scrollX,joined.scrollY,joined.screenX,joined.screenY,joined.pose,joined.layer,joined.variable(4),joined.variable(8),joined.variable(1)))
        assertFalse(story.joins(joined,15));assertEquals(1,joined.bytes()[500].toInt())
        val letter=assertNotNull(CabinDoors.travel(joined.travel(0,40,25,304,184,12,0,mapOf(0 to 1)),1))
        assertEquals(3,letter.variable(8));assertEquals(0,letter.bytes()[500].toInt())
    }
    @Test fun `reward slides to native resting position and needs an input`() {
        val pages=data.get("sword_story").get("shop").get("3")
        val dialogue=FieldDialogue(0,pages)
        dialogue.advance();dialogue.advance()
        assertTrue(dialogue.isReward);assertFalse(dialogue.pageComplete)
        dialogue.update(1f/70f);assertEquals(318f,dialogue.rewardX)
        dialogue.update(30f/70f);assertEquals(14f,dialogue.rewardX);assertTrue(dialogue.pageComplete)
        dialogue.advance();assertFalse(dialogue.isReward);assertFalse(dialogue.finished)
    }
    @Test fun `join actor positions and poses match native auto animation checkpoints`() {
        val timeline=OpeningTimeline(data,5,5)
        assertTrue(timeline.continuesField);assertEquals(0f,timeline.fadeAlpha)
        val expected=data.get("field_native_checkpoints").get("5").filter { it.getString("op")=="C" }
        var index=0;var previous:Int?=null
        repeat(2000) {
            if(timeline.finished) return@repeat
            assertEquals(0f,timeline.fadeAlpha)
            if(timeline.page!=null && timeline.dialogueId!=previous) {
                val row=expected[index++]
                assertEquals(row.getInt("dialogue"),timeline.dialogueId)
                row.get("actors").forEachIndexed { i,a ->
                    val actual=timeline.actors[i]
                    assertEquals(listOf(a.getFloat("x"),a.getFloat("y"),a.getInt("frame").toFloat()),listOf(actual.x,actual.y,actual.frame.toFloat()))
                }
                previous=timeline.dialogueId
            }
            if(timeline.page!=null) timeline.advance() else timeline.update(1f/70f)
        }
        assertTrue(timeline.finished);assertEquals(2,index)
        assertEquals(112f,timeline.scrollX);assertEquals(56f,timeline.scrollY)
    }
}
