package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.field.*
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class TownObjectsTest {
    private fun json(path:String)=JsonReader().parse(Files.readString(Path.of("../$path")))
    private val opening=json("assets/opening/opening.json")
    private val report=json("docs/restoration/town-objects-native.json")
    private val objects=json("assets/data/field/objects.json")
    private val rewards=FieldRewards(json("assets/ui/equipment/equipment.json"),json("assets/ui/rewards/rewards.json"))
    private val story=FieldStory(json("assets/data/events/story.json"),opening)
    private val archive=EventArchive(Files.readAllBytes(Path.of("../assets/data/events/area00.gm")))
    private val characters=(objects.get("font").getString("characters")+opening.get("font").getString("characters")+json("assets/data/field/indoor-dialogues.json").get("font").getString("characters")+rewards.data.get("font").getString("characters")+
        json("assets/ui/equipment/equipment.json").get("rects").filter { it.name.startsWith("glyph-") }.map { it.name.substring(6).toInt().toChar() }.joinToString("")).toSet()
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun snapshot(script:FieldScript,row:JsonValue) {
        assertEquals(row.getInt("offset"),script.machine.offset);assertEquals(row.getLong("instructions"),script.machine.executedInstructions)
        val b=ByteBuffer.allocate(600).order(ByteOrder.LITTLE_ENDIAN).apply { script.machine.variables().forEach { putShort(it.toShort()) } }.array()
        assertEquals(row.getString("variables"),MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) })
    }
    @Test fun `all mapped objects and their save tile reward animation states match native execution`() {
        var chests=0;var animations=0
        for(row in report.get("cases")) {
            val before=bytes(row.getString("before"));val source=DosSave.parse(before);val map=opening.get("maps").get(source.map.toString())
            val cells=map.get("cells").map { it[0].asInt() }.toIntArray()
            val blocked=mutableListOf<FieldScript.Blocked>()
            val script=assertNotNull(FieldScript.prepare(archive,source,row.getInt("event"),opening.get("maps"),characters,true,rewards=rewards,story=story,
                canToggleChest={ FieldChests.allowed(map,row.getInt("x"),row.getInt("y")) }) { blocked+=it },"${row.getString("name")}: $blocked")
            for(expected in row.get("requests")) {
                val request=script.next();snapshot(script,expected);assertSame(request,script.next())
                when(request) {
                    is EventMachine.Stop.Dialogue -> { assertEquals("dialogue",expected.getString("kind"));assertContentEquals(bytes(expected.getString("raw")),request.bytes());script.resumeDialogue() }
                    is EventMachine.Stop.Reward -> {
                        val page=script.rewardPages(request)[0]
                        assertEquals(expected.getString("notice"),page.getString("text"))
                        if(request.category>=3) {
                            assertEquals("empty",page.getString("kind"));val dialogue=FieldDialogue(-1,script.rewardPages(request))
                            assertTrue(dialogue.isReward);assertFalse(dialogue.isMoneyReward)
                        }
                        script.receiveReward(request);assertContentEquals(bytes(expected.getString("credited")),script.save.bytes());script.resumeService()
                    }
                    is EventMachine.Stop.Panel -> script.resumeService()
                    is EventMachine.Stop.Chest -> {
                        assertEquals("chest",expected.getString("kind"))
                        val changes=FieldChests.changes(map,cells,row.getInt("x"),row.getInt("y"))
                        assertEquals(expected.get("changes").map { listOf(it.getInt("index"),it.getInt("tid"),it.getInt("attr")) },changes.map { listOf(it.getInt("index"),it.getInt("tid"),it.getInt("attr")) })
                        changes.forEach { cells[it.getInt("index")]=it.getInt("tid") };script.resumeChest(request)
                        assertContentEquals(bytes(expected.getString("after")),script.save.bytes());chests++
                        assertFailsWith<IllegalStateException> { script.resumeChest(request) }
                    }
                    is EventMachine.Stop.Animation -> {
                        assertEquals("animation",expected.getString("kind"));val plan=script.beginAnimation(request,story)
                        for(call in expected.get("calls")) {
                            val child=script.child(call.getInt("argument"))
                            for(service in call.get("requests")) {
                                val stop=assertIs<EventMachine.Stop.Dialogue>(child.next());snapshot(child,service)
                                assertContentEquals(bytes(service.getString("raw")),stop.bytes());child.resumeDialogue()
                            }
                            assertIs<EventMachine.Stop.Finished>(child.next());assertContentEquals(bytes(call.getString("after")),child.save.bytes());script.adopt(child.save)
                        }
                        script.finishAnimation(request,story,plan);assertContentEquals(bytes(expected.getString("after")),script.save.bytes());animations++
                    }
                    else -> fail("사물 조사에 예상하지 못한 명령이 있습니다.")
                }
            }
            assertIs<EventMachine.Stop.Finished>(script.next());snapshot(script,row.get("end"));assertContentEquals(bytes(row.getString("after")),script.save.bytes(),row.getString("name"))
            assertContentEquals(before,source.bytes())
        }
        assertEquals(90,chests);assertEquals(18,animations)
    }
    @Test fun `front tile investigation matches the original action key routine`() {
        val rules=FieldRules(Files.readString(Path.of("../assets/data/field/field.json"))) { Files.readAllBytes(Path.of("../assets/data/field/$it")) }
        for(row in report.get("fronts")) {
            val pose=mapOf(1 to 0,2 to 4,4 to 8,8 to 12).getValue(row[3].asInt())
            assertEquals(if(row[4].isNull)null else row[4].asInt(),rules.interaction(row[0].asInt(),row[1].asFloat(),row[2].asFloat(),pose))
        }
    }
    @Test fun `chests refuse unsupported positions before execution and preserve upper tile flags`() {
        val row=report.get("cases").first { it.get("requests").any { r -> r.getString("kind")=="chest" } }
        val source=DosSave.parse(bytes(row.getString("before")));val before=source.bytes()
        assertNull(FieldScript.prepare(archive,source,row.getInt("event"),opening.get("maps"),characters,true,rewards=rewards,story=story))
        val map=opening.get("maps").get(source.map.toString());val cells=map.get("cells").map { it[0].asInt() or 0x6000 }.toIntArray()
        for(change in FieldChests.changes(map,cells,row.getInt("x"),row.getInt("y"))) assertEquals(cells[change.getInt("index")] and 0xf000,change.getInt("tid") and 0xf000)
        for((x,y) in listOf(-1 to 0,0 to -1,map.getInt("width")*8-16 to 0,0 to map.getInt("height")*8-8)) assertFailsWith<IllegalArgumentException> { FieldChests.changes(map,cells,x,y) }
        assertContentEquals(before,source.bytes())
    }
}
