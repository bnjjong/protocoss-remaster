package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.Viewport
import com.protocoss.ProtocossGame
import com.protocoss.player.Player
import com.protocoss.prologue.ProloguePlayer
import java.nio.file.Files
import java.nio.file.Path

/** 원작 기록의 기준 장면과 실제 GPU 출력을 비교하고 종료·스킵·일시 정지를 검사한다. */
object PrologueCheck {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("protocoss-prologue-qa-")
        try {
            val realtime = args.contains("--realtime")
            val check = PrologueApplication(directory, realtime)
            Lwjgl3Application(check, Lwjgl3ApplicationConfiguration().apply {
                setTitle("프롤로그 자동 검사")
                setWindowedMode(640, 400)
                setInitialVisible(false)
                useVsync(false)
                setForegroundFPS(if (realtime) 60 else 0)
            })
            check(check.completed)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}

private class PrologueApplication(private val directory: Path, private val realtime: Boolean) : ApplicationAdapter() {
    private var game = ProtocossGame(null, directory)
    private lateinit var input: Input
    private lateinit var graphics: Graphics
    private lateinit var nativeGraphics: Graphics
    private var maxAudioDifference = 0.0
    private var delta = 0f
    private val pressed = mutableSetOf<Int>()
    private var held = false
    private var step = 0
    private var resizeTicks = 0
    private var compared = 0
    private var movie: ProloguePlayer? = null
    private var music: com.badlogic.gdx.audio.Music? = null
    private val references = JsonReader().parse(Files.readString(Path.of("assets/prologue/manifest.json"))).get("references")
    var completed = false
        private set

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String) = ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible = true }.get(game) as T
    private fun scene() = field<Any>("scene").toString()
    private fun currentMovie() = field<ProloguePlayer>("prologue")
    private fun currentMusic(movie: ProloguePlayer): com.badlogic.gdx.audio.Music =
        ProloguePlayer::class.java.getDeclaredField("music").apply { isAccessible = true }.get(movie) as com.badlogic.gdx.audio.Music

    override fun create() {
        // 자동 검사는 시스템 음량과 다른 앱을 건드리지 않고 이 앱의 음악만 음소거한다.
        val nativeAudio = Gdx.audio
        Gdx.audio = object : com.badlogic.gdx.Audio by nativeAudio {
            override fun newMusic(file: com.badlogic.gdx.files.FileHandle) = nativeAudio.newMusic(file).apply { volume = 0f }
        }
        val originalInput = Gdx.input
        val originalGraphics = Gdx.graphics
        nativeGraphics = originalGraphics
        input = object : Input by originalInput {
            override fun isKeyJustPressed(key: Int) = key in pressed
            override fun isKeyPressed(key: Int) = held && key == Input.Keys.W
        }
        graphics = object : Graphics by originalGraphics { override fun getDeltaTime() = delta }
        Gdx.input = input; Gdx.graphics = graphics
        game.create()
    }

    override fun render() {
        Gdx.input = input; Gdx.graphics = graphics
        pressed.clear(); delta = 0f
        if (realtime && step == 2) {
            delta = nativeGraphics.deltaTime
            game.render()
            if (scene() == "OPENING") {
                check(movie!!.finished && !music!!.isPlaying)
                completed = true
                Files.createDirectories(Path.of("desktop/build/reports/prologue"))
                Files.writeString(Path.of("desktop/build/reports/prologue/realtime.txt"),
                    "전체 ${movie!!.timeline.elapsed}초 자연 종료, 음악 최대 시간 차 $maxAudioDifference 초\n")
                println("실시간 프롤로그 완료: ${movie!!.timeline.elapsed}초, 음악 최대 시간 차 $maxAudioDifference 초")
                Gdx.app.exit()
            } else if (movie!!.timeline.elapsed > 1 && music!!.isPlaying) {
                maxAudioDifference = maxOf(maxAudioDifference, kotlin.math.abs(movie!!.timeline.elapsed - music!!.position))
                check(maxAudioDifference < 0.3) { "음악과 영상의 시간 차가 큽니다: $maxAudioDifference 초" }
            }
            return
        }
        when (step) {
            0 -> check(scene() == "TITLE")
            1 -> pressed.add(Input.Keys.ENTER)
            2 -> { held = true; delta = references[0].getDouble("time").toFloat(); pressed.add(Input.Keys.T) }
            3 -> { game.pause(); delta = 30f }
            4 -> { game.resume() }
            5 -> {
                if (resizeTicks == 0) check(graphics.setWindowedMode(1000, 700))
            }
            in 6..15 -> {
                val target = references[step - 5].getDouble("time")
                delta = (target - currentMovie().timeline.elapsed).toFloat()
            }
            16 -> delta = 100f
            17 -> {
                held = false
                game.dispose()
                game = ProtocossGame(null, directory)
                game.create(); game.resize(graphics.width, graphics.height)
            }
            18 -> pressed.add(Input.Keys.ENTER)
            19 -> { held = true; pressed.add(Input.Keys.ESCAPE) }
            20 -> {
                held = false
                game.dispose()
                game = ProtocossGame(null, directory)
                game.create(); game.resize(graphics.width, graphics.height)
            }
            21 -> pressed.add(Input.Keys.ENTER)
            22 -> pressed.add(Input.Keys.SPACE)
            23 -> {
                game.dispose()
                game = ProtocossGame(null, directory)
                game.create(); game.resize(graphics.width, graphics.height)
            }
            24 -> pressed.add(Input.Keys.ENTER)
            25 -> pressed.add(Input.Keys.ENTER)
            26 -> {
                completed = true
                Files.writeString(Path.of("desktop/build/reports/prologue/result.txt"), "27개 재생·종료·스킵 단계 통과, $compared 픽셀 일치\n")
                println("프롤로그 실행 검사 완료: 27단계, $compared 픽셀 일치")
                Gdx.app.exit(); return
            }
        }
        game.render()
        when (step) {
            1, 18, 21, 24 -> {
                check(scene() == "PROLOGUE") { "새 게임이 프롤로그를 거치지 않았습니다." }
                movie = currentMovie(); music = currentMusic(movie!!)
                check(movie!!.timeline.stream.frameIndex == 0)
                check(music!!.isPlaying) { "프롤로그 음악이 재생되지 않습니다." }
                check(field<Player>("player").x == 92f)
            }
            2 -> { compare(0); capture("opening") }
            3 -> {
                check(currentMovie().timeline.stream.frameIndex == references[0].getInt("frame"))
                check(!music!!.isPlaying)
            }
            4 -> check(music!!.isPlaying)
            5 -> {
                if (++resizeTicks < 4) return
                compare(0); capture("resized")
            }
            in 6..15 -> { compare(step - 5); if (step == 15) capture("final-scene") }
            16, 19, 22, 25 -> {
                check(scene() == "OPENING") { "프롤로그 종료 뒤 오두막 시작 연출로 전환되지 않았습니다." }
                check(field<Any?>("prologue") == null)
                check(!music!!.isPlaying)
                val player = field<Player>("player")
                check(player.x == 92f && player.y == 148f) { "프롤로그 입력이 필드 이동으로 전달되었습니다." }
                check(!field<com.protocoss.dialogue.DialogueManager>("dialogueManager").isActive)
                if (step == 16) capture("completed-field")
            }
        }
        println("프롤로그 검사 $step: ${scene()} 통과")
        step++
    }

    private fun compare(reference: Int) {
        val expected = references[reference]
        val movie = currentMovie()
        check(movie.timeline.stream.frameIndex == expected.getInt("frame")) { "원작 프레임 순서 불일치" }
        val image = Pixmap(Gdx.files.internal("docs/images/${expected.getString("file")}"))
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, graphics.backBufferWidth, graphics.backBufferHeight, true)
        val viewport = field<Viewport>("viewport")
        val dpiX = graphics.backBufferWidth.toFloat() / graphics.width
        val dpiY = graphics.backBufferHeight.toFloat() / graphics.height
        val left = viewport.screenX * dpiX
        val top = graphics.backBufferHeight - (viewport.screenY + viewport.screenHeight) * dpiY
        val scaleX = viewport.screenWidth * dpiX / 320f
        val scaleY = viewport.screenHeight * dpiY / 200f
        for (y in 0 until 200) for (x in 0 until 320) {
            val px = (left + (x + 0.5f) * scaleX).toInt()
            val py = (top + (y + 0.5f) * scaleY).toInt()
            val index = (py * graphics.backBufferWidth + px) * 4
            val actual = ((pixels[index].toInt() and 255) shl 24) or ((pixels[index + 1].toInt() and 255) shl 16) or
                ((pixels[index + 2].toInt() and 255) shl 8) or (pixels[index + 3].toInt() and 255)
            check(actual == image.getPixel(x, y)) { "원작 영상과 실제 화면 불일치: $reference, $x,$y" }
            compared++
        }
        image.dispose()
        check(field<Player>("player").x == 92f && field<Player>("player").y == 148f)
    }

    private fun capture(name: String) {
        val image = Pixmap(graphics.backBufferWidth, graphics.backBufferHeight, Pixmap.Format.RGBA8888)
        image.pixels.put(ScreenUtils.getFrameBufferPixels(0, 0, image.width, image.height, true)).flip()
        PixmapIO.writePNG(Gdx.files.local("desktop/build/reports/prologue/$name.png"), image)
        image.dispose()
    }
    override fun resize(width: Int, height: Int) = game.resize(width, height)
    override fun pause() = game.pause()
    override fun resume() = game.resume()
    override fun dispose() = game.dispose()
}
