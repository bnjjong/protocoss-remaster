package com.protocoss.prologue

import com.badlogic.gdx.utils.JsonReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class PrologueStreamTest {
    private fun movie(durations: List<Double> = listOf(0.25, 0.5), invalidPalette: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(0x50563031); out.writeShort(320); out.writeShort(200); out.writeInt(durations.size)
            var previous = ByteArray(64768)
            durations.forEachIndexed { index, duration ->
                val frame = ByteArray(64768)
                frame[600] = if (invalidPalette) 64 else 63
                frame[601] = 1; frame[602] = 31
                for (i in 768 until frame.size) frame[i] = 200.toByte()
                if (index == 1) frame[768] = 0
                out.writeDouble(duration)
                out.write(ByteArray(frame.size) { (frame[it].toInt() xor previous[it].toInt()).toByte() })
                previous = frame
            }
        }
        return bytes.toByteArray()
    }

    @Test fun `signed color indices and palette scaling produce opaque RGBA`() {
        PrologueStream(ByteArrayInputStream(movie())).use { stream ->
            assertTrue(stream.nextFrame())
            val target = ByteBuffer.allocate(320 * 200 * 4)
            stream.writeRgba(target)
            assertEquals(listOf(252, 4, 124, 255), (0..3).map { target.get(it).toInt() and 255 })
            assertTrue(stream.nextFrame())
            stream.writeRgba(target)
            assertEquals(listOf(0, 0, 0, 255), (0..3).map { target.get(it).toInt() and 255 })
            assertEquals(252, target.get(4).toInt() and 255)
            assertFalse(stream.nextFrame())
        }
    }

    @Test fun `timeline retains each hold and completes only after the final hold`() {
        PrologueTimeline(PrologueStream(ByteArrayInputStream(movie()))).use { timeline ->
            timeline.update(0.249)
            assertEquals(0, timeline.stream.frameIndex)
            timeline.update(0.001)
            assertEquals(1, timeline.stream.frameIndex)
            assertFalse(timeline.finished)
            timeline.update(0.49)
            assertFalse(timeline.finished)
            timeline.update(0.01)
            assertTrue(timeline.finished)
            assertEquals(0.75, timeline.elapsed, 1e-9)
        }
    }

    @Test fun `large delta consumes frames and later updates do not reopen the movie`() {
        PrologueTimeline(PrologueStream(ByteArrayInputStream(movie()))).use { timeline ->
            timeline.update(20.0)
            assertTrue(timeline.finished)
            timeline.update(1.0)
            assertEquals(0.75, timeline.elapsed, 1e-9)
        }
    }

    @Test fun `invalid time palette and truncated payload are rejected`() {
        PrologueStream(ByteArrayInputStream(movie(listOf(Double.NaN)))).use {
            assertFailsWith<IllegalArgumentException> { it.nextFrame() }
        }
        PrologueStream(ByteArrayInputStream(movie(invalidPalette = true))).use {
            assertFailsWith<IllegalArgumentException> { it.nextFrame() }
        }
        val raw = movie()
        PrologueStream(ByteArrayInputStream(raw.copyOf(raw.size / 2))).use {
            assertFailsWith<EOFException> { while (it.nextFrame()) {} }
        }
    }

    @Test fun `every packaged frame matches the original machine recording`() {
        val directory = Path.of("../assets/prologue")
        val hashes = JsonReader().parse(Files.readString(directory.resolve("frame-hashes.json")))
        val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
        var index = 0
        var duration = 0.0
        PrologueStream(Files.newInputStream(directory.resolve("prologue.pvid"))).use { stream ->
            while (stream.nextFrame()) {
                assertEquals(hashes[index].asString(), stream.frameHash(), "원작 프레임 $index")
                duration += stream.frameDuration
                index++
            }
        }
        assertEquals(manifest.getInt("frame_count"), index)
        assertEquals(manifest.getDouble("duration_seconds"), duration, 1e-8)
    }
}
