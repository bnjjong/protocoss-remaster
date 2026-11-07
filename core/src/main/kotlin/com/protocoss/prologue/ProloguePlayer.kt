package com.protocoss.prologue

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable

/** 영상은 두 프레임 버퍼만 유지하고, 음악은 스트리밍으로 재생한다. */
class ProloguePlayer : Disposable {
    val timeline = PrologueTimeline(PrologueStream(Gdx.files.internal("assets/prologue/prologue.pvid").read()))
    private val pixmap = Pixmap(320, 200, Pixmap.Format.RGBA8888)
    private val texture: Texture
    private val music: Music?
    private var paused = false
    private var disposed = false

    init {
        timeline.stream.writeRgba(pixmap.pixels)
        texture = Texture(pixmap).apply { setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        music = try {
            Gdx.audio.newMusic(Gdx.files.internal("assets/prologue/prologue.ogg"))
        } catch (e: Exception) {
            Gdx.app.error("Prologue", "프롤로그 음악을 열지 못했습니다. 영상은 계속 재생합니다.", e)
            null
        }
    }

    val finished: Boolean get() = timeline.finished
    fun play() { music?.play() }
    fun update(delta: Float) {
        if (paused) return
        val previous = timeline.stream.frameIndex
        timeline.update(delta.toDouble())
        if (previous != timeline.stream.frameIndex) {
            timeline.stream.writeRgba(pixmap.pixels)
            texture.draw(pixmap, 0, 0)
        }
    }

    fun render(batch: SpriteBatch) {
        batch.begin()
        batch.draw(texture, 0f, 0f, 320f, 200f)
        batch.end()
    }

    fun pause() { paused = true; music?.pause() }
    fun resume() { if (paused) { paused = false; music?.play() } }
    override fun dispose() {
        if (disposed) return
        disposed = true
        music?.stop()
        music?.dispose()
        texture.dispose()
        pixmap.dispose()
        timeline.close()
    }
}
