package com.protocoss.script

import com.badlogic.gdx.utils.JsonReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class EventArchiveTest {
    private val folder=Path.of("../assets/data/events")
    private val manifest=JsonReader().parse(Files.readString(folder.resolve("manifest.json")))

    @Test fun `all six archives resolve entries and defaults like original machine code`() {
        val native=JsonReader().parse(Files.readString(Path.of("../docs/restoration/event-entry-native.json")))
        val archives=manifest.get("archives").associate { row ->
            val raw=Files.readAllBytes(folder.resolve(row.getString("file")))
            val hash=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            assertEquals(row.getString("sha256"),hash)
            assertEquals(native.get("sources").getString(row.getString("source")),hash)
            val archive=EventArchive(raw)
            assertEquals(row.getInt("groups"),archive.groups.size)
            assertEquals(row.getInt("routes"),archive.groups.sumOf { it.routes.size })
            row.getInt("area") to archive
        }
        for(case in native.get("cases")) {
            val archive=archives.getValue(case[0].asInt())
            val actual=archive.resolve(case[1].asInt(),case[2].asInt())
            assertEquals(case[3].asInt(),actual,case.toString())
            assertEquals(case[4].asInt(),archive.byte(actual),case.toString())
        }
    }

    @Test fun `corrupt lengths offsets and unsupported dispatch forms are rejected`() {
        val raw=Files.readAllBytes(folder.resolve("area00.gm"))
        val count=(raw[4].toInt() and 255) or ((raw[5].toInt() and 255) shl 8)
        val start=6+count*2
        for(invalid in listOf(
            raw.copyOf(5),raw.copyOf(raw.size-1),
            raw.copyOf().apply { this[6]=(-1).toByte();this[7]=(-1).toByte() },
            raw.copyOf().apply { this[start+1]=(2 xor 127).toByte() },
            raw.copyOf().apply { this[start]=(0xf9 xor 127).toByte() }
        )) assertFailsWith<IllegalArgumentException> { EventArchive(invalid) }
        val archive=EventArchive(raw)
        assertFailsWith<IllegalArgumentException> { archive.resolve(-1,0) }
        assertFailsWith<IllegalArgumentException> { archive.resolve(archive.groups.size,0) }
        assertFailsWith<IllegalArgumentException> { archive.resolve(0,65536) }
        assertFailsWith<IllegalArgumentException> { archive.byte(archive.codeSize) }
    }
}
