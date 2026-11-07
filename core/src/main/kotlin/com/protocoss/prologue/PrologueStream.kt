package com.protocoss.prologue

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** 원작 실행으로 기록한 팔레트·색 번호의 차이를 순서대로 복원한다. */
class PrologueStream(input: InputStream) : Closeable {
    private val source = DataInputStream(BufferedInputStream(GZIPInputStream(input)))
    val width: Int
    val height: Int
    val frameCount: Int
    var frameIndex = -1
        private set
    var frameDuration = 0.0
        private set
    private val frame: ByteArray
    private val delta: ByteArray
    private var checkedEnd = false

    init {
        try {
            require(source.readInt() == 0x50563031) { "프롤로그 파일 표식이 다릅니다." }
            width = source.readUnsignedShort()
            height = source.readUnsignedShort()
            frameCount = source.readInt()
            require(width == 320 && height == 200 && frameCount in 1..20000) { "프롤로그 화면 규격이 다릅니다." }
            frame = ByteArray(768 + width * height)
            delta = ByteArray(frame.size)
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    fun nextFrame(): Boolean {
        if (frameIndex + 1 == frameCount) {
            if (!checkedEnd) {
                require(source.read() == -1) { "프롤로그 뒤에 해석하지 않은 데이터가 있습니다." }
                checkedEnd = true
            }
            return false
        }
        val duration = source.readDouble()
        require(duration.isFinite() && duration > 0 && duration <= 60) { "프롤로그 재생 시간이 잘못되었습니다." }
        source.readFully(delta)
        for (i in frame.indices) frame[i] = (frame[i].toInt() xor delta[i].toInt()).toByte()
        require((0 until 768).all { (frame[it].toInt() and 255) <= 63 }) { "프롤로그 팔레트가 잘못되었습니다." }
        frameDuration = duration
        frameIndex++
        return true
    }

    fun writeRgba(target: ByteBuffer) {
        check(frameIndex >= 0)
        target.clear()
        for (i in 768 until frame.size) {
            val color = (frame[i].toInt() and 255) * 3
            target.put((frame[color] * 4).toByte())
            target.put((frame[color + 1] * 4).toByte())
            target.put((frame[color + 2] * 4).toByte())
            target.put(255.toByte())
        }
        target.flip()
    }

    fun frameHash(): String = MessageDigest.getInstance("SHA-256").digest(frame).joinToString("") { "%02x".format(it) }
    override fun close() = source.close()
}

/** 재생 시간에 맞춰 필요한 프레임을 읽으며, 마지막 장면의 대기도 끝까지 유지한다. */
class PrologueTimeline(val stream: PrologueStream) : Closeable {
    var elapsed = 0.0
        private set
    var finished = false
        private set
    private var frameEnd: Double

    init {
        check(stream.nextFrame())
        frameEnd = stream.frameDuration
    }

    fun update(delta: Double) {
        require(delta.isFinite() && delta >= 0)
        if (finished) return
        elapsed += delta
        while (elapsed >= frameEnd) {
            if (!stream.nextFrame()) { elapsed = frameEnd; finished = true; return }
            frameEnd += stream.frameDuration
        }
    }

    override fun close() = stream.close()
}
