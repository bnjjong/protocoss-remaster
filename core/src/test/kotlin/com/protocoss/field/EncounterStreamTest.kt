package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import com.protocoss.prologue.PrologueStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EncounterStreamTest {
    @Test fun `all twenty five displayed frames match native decoder state`() {
        val root=Path.of("../assets/encounter")
        val metadata=JsonReader().parse(Files.readString(root.resolve("manifest.json")))
        PrologueStream(Files.newInputStream(root.resolve("encounter.pvid"))).use { stream ->
            for(hash in metadata.get("frame_state_sha256")) {
                assertTrue(stream.nextFrame());assertEquals(hash.asString(),stream.frameHash())
                assertEquals(3*65536.0/1193182,stream.frameDuration,1e-10)
            }
            repeat(65) { assertTrue(stream.nextFrame()) }
            assertFalse(stream.nextFrame())
        }
    }
    @Test fun `place name waits for a new key and completes slide out`() {
        val banner=PlaceBanner("엘가임 마을")
        banner.update(1f);assertTrue(banner.waitingForInput)
        val x=banner.x;banner.update(60f);assertEquals(x,banner.x);assertFalse(banner.finished)
        banner.advance();banner.update(1f);assertTrue(banner.finished)
    }
}
