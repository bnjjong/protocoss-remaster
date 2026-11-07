package com.protocoss.save

import com.badlogic.gdx.utils.JsonReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.nio.file.attribute.FileTime
import java.time.ZonedDateTime

/** 원본 DATA는 읽기만 하며 새 저장은 사용자 전용 폴더에 쓴다. */
class SaveRepository(val directory: Path, private val originalDirectory: Path?) {
    data class Entry(val label: String, val file: Path, val save: DosSave?, val error: String? = null)
    data class Loaded(val save: DosSave, val nextDialogue: Int = 0)

    fun localEntries(): List<Entry> = (0..3).map { read("저장 ${it + 1}", directory.resolve("SAVE$it.DAT")) }
    fun loadEntries(): List<Entry> = localEntries() + (originalDirectory?.let { game ->
        (0..3).map { read("DOS ${it + 1}", game.resolve("DATA/SAVE$it.DAT")) }.filter { Files.exists(it.file) }
    } ?: emptyList())

    private fun read(label: String, path: Path): Entry {
        if (!Files.exists(path)) return Entry(label, path, null)
        return try {
            Entry(label, path, DosSave.parse(Files.readAllBytes(path)))
        } catch (e: Exception) {
            Entry(label, path, null, "읽을 수 없는 저장 파일")
        }
    }

    fun load(entry: Entry): Loaded {
        val bytes = Files.readAllBytes(entry.file)
        val save = DosSave.parse(bytes)
        var next = 0
        // 원본 DOS 저장은 부가 정보가 없다. 일치하는 리마스터 부가 정보만 적용한다.
        if (entry.file.parent == directory) {
            val sidecar = entry.file.resolveSibling(entry.file.fileName.toString() + ".json")
            if (Files.exists(sidecar)) try {
                val json = JsonReader().parse(Files.readString(sidecar))
                if (json.getInt("version") == 1 && json.getString("sha256") == hash(bytes)) {
                    next = json.getInt("nextDialogue").coerceIn(0, 2)
                }
            } catch (_: Exception) { /* DOS 본문이 유효하면 선택적인 부가 정보 없이 불러온다. */ }
        }
        return Loaded(save, next)
    }

    fun write(slot: Int, save: DosSave, nextDialogue: Int) {
        require(originalDirectory == null || directory.toAbsolutePath().normalize() !=
            originalDirectory.resolve("DATA").toAbsolutePath().normalize()) { "원본 DATA 폴더에는 저장할 수 없습니다." }
        require(slot in 0..3 && nextDialogue in 0..2)
        Files.createDirectories(directory)
        val now = ZonedDateTime.now().withNano(0)
        val time = now.withSecond(now.second / 2 * 2)
        val bytes = save.forTime(time.toLocalTime())
        val target = directory.resolve("SAVE$slot.DAT")
        val metadata = """{"version":1,"sha256":"${hash(bytes)}","nextDialogue":$nextDialogue}"""
        // 같은 폴더에 완성한 파일을 교체해 중간 쓰기로 기존 저장이 잘리는 일을 피한다.
        atomicWrite(target.resolveSibling("SAVE$slot.DAT.json"), metadata.toByteArray())
        atomicWrite(target, bytes)
        Files.setLastModifiedTime(target, FileTime.from(time.toInstant()))
    }

    private fun atomicWrite(target: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(directory, ".save-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
