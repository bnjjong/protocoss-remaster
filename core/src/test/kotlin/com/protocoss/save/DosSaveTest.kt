package com.protocoss.save

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.LocalTime
import kotlin.test.*

class DosSaveTest {
    private fun fixture(): ByteArray = ByteArray(DosSave.SIZE) { (it * 37).toByte() }.also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(92, 0); putInt(96, 16); putInt(100, 0); putInt(104, 25)
            putInt(108, 160); putInt(112, 112)
        }
    }

    @Test fun `native fields and all opaque bytes survive parsing`() {
        val raw = fixture()
        val save = DosSave.parse(raw)
        assertEquals(0, save.area); assertEquals(16, save.map)
        assertEquals(144f, save.worldX); assertEquals(104f, save.worldY(400f))
        assertContentEquals(raw, save.bytes())
        raw[200] = 0
        assertNotEquals(raw[200], save.bytes()[200])
        val moved = save.atPosition(180f, 120f, 400f).bytes()
        assertContentEquals(save.bytes().copyOfRange(0, 108), moved.copyOfRange(0, 108))
        assertContentEquals(save.bytes().copyOfRange(116, 2944), moved.copyOfRange(116, 2944))
        assertEquals(180f, DosSave.parse(moved).worldX)
        assertEquals(120f, DosSave.parse(moved).worldY(400f))
    }

    @Test fun `moving beyond the old viewport updates DOS scroll coordinates`() {
        val moved = DosSave.parse(fixture()).atPosition(500f, 600f, 800f, 640f)
        assertEquals(500f, moved.worldX)
        assertEquals(600f, moved.worldY(800f))
        assertTrue(moved.scrollX in 0..40)
        assertTrue(moved.screenX in 16..336 && moved.screenY in 16..216)
    }

    @Test fun `invalid files fail before changing a game`() {
        assertFailsWith<IllegalArgumentException> { DosSave.parse(ByteArray(2943)) }
        assertFailsWith<IllegalArgumentException> { DosSave.parse(ByteArray(2945)) }
        val raw = fixture()
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(92, 6)
        assertFailsWith<IllegalArgumentException> { DosSave.parse(raw) }
    }

    @Test fun `native time guard changes only the last word`() {
        val raw = fixture()
        val written = DosSave.parse(raw).forTime(LocalTime.of(20, 1, 58))
        assertContentEquals(raw.copyOfRange(0, 2942), written.copyOfRange(0, 2942))
        val encoded = ByteBuffer.wrap(written).order(ByteOrder.LITTLE_ENDIAN).getShort(2942).toInt() and 65535
        assertEquals(((20 shl 11) or (1 shl 5) or 29) xor 0x7f7f, encoded)
    }

    @Test fun `fresh repository loads written state without modifying DOS originals`() {
        val root = Files.createTempDirectory("protocoss-save-test-")
        try {
            val original = root.resolve("original")
            Files.createDirectories(original.resolve("DATA"))
            val raw = fixture()
            Files.write(original.resolve("DATA/SAVE0.DAT"), raw)
            assertFailsWith<IllegalArgumentException> {
                SaveRepository(original.resolve("DATA"), original).write(0, DosSave.parse(raw), 0)
            }
            val directory = root.resolve("saves")
            val saves = SaveRepository(directory, original)
            assertNull(saves.localEntries()[0].save)
            assertEquals(5, saves.loadEntries().size)
            saves.write(0, DosSave.parse(raw), 2)
            val fresh = SaveRepository(directory, original)
            val loaded = fresh.load(fresh.localEntries()[0])
            assertEquals(2, loaded.nextDialogue)
            assertContentEquals(raw.copyOfRange(0, 2942), loaded.save.bytes().copyOfRange(0, 2942))
            assertContentEquals(raw, Files.readAllBytes(original.resolve("DATA/SAVE0.DAT")))
            Files.writeString(directory.resolve("SAVE0.DAT.json"), "broken")
            assertEquals(0, fresh.load(fresh.localEntries()[0]).nextDialogue)
            Files.write(directory.resolve("SAVE1.DAT"), byteArrayOf(1, 2))
            assertNotNull(fresh.localEntries()[1].error)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun `new game resets native starting position without erasing initial party data`() {
        val raw = fixture()
        val game = DosSave.newGame(raw)
        assertEquals(2, game.map)
        assertEquals(96, game.screenX); assertEquals(56, game.screenY)
        assertContentEquals(raw.copyOfRange(119, 2942), game.bytes().copyOfRange(119, 2942))
    }
}
