package com.protocoss.desktop

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.Input
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.Viewport
import com.protocoss.ProtocossGame
import com.protocoss.player.Player
import kotlin.math.abs
import kotlin.math.roundToInt

/** 실제 게임을 고정 입력·시간으로 실행해 경계 이동과 배경 표시를 검사한다. */
object FieldBoundsCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val application = BoundsApplication()
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("필드 경계 자동 검사")
            setWindowedMode(640, 400)
            setInitialVisible(false)
            useVsync(false)
            setForegroundFPS(0)
        }
        Lwjgl3Application(application, config)
        check(application.completed) { "필드 경계 검사를 마치기 전에 실행이 종료되었습니다." }
    }
}

private class BoundsApplication : ApplicationAdapter() {
    private data class Scenario(val name: String, val keys: Set<Int>, val frames: Int)

    private val game = ProtocossGame()
    private val held = mutableSetOf<Int>()
    private val justPressed = mutableSetOf<Int>()
    private lateinit var nativeGraphics: Graphics
    private lateinit var fixedGraphics: Graphics
    private lateinit var fixedInput: Input
    private lateinit var background: Pixmap
    private lateinit var sprite: Pixmap
    private var scenarioIndex = 0
    private var tick = 0
    private var comparedPixels = 0
    var completed = false
        private set

    private val scenarios = buildList {
        add(Scenario("initial", emptySet(), 1))
        val directions = listOf(
            "left" to setOf(Input.Keys.A), "right" to setOf(Input.Keys.D),
            "up" to setOf(Input.Keys.W), "down" to setOf(Input.Keys.S),
            "up-left" to setOf(Input.Keys.W, Input.Keys.A),
            "up-right" to setOf(Input.Keys.W, Input.Keys.D),
            "down-left" to setOf(Input.Keys.S, Input.Keys.A),
            "down-right" to setOf(Input.Keys.S, Input.Keys.D)
        )
        for ((name, keys) in directions) {
            add(Scenario("walk-$name", keys, 180))
            add(Scenario("dash-$name", keys + Input.Keys.SHIFT_LEFT, 90))
        }
        add(Scenario("gallery", emptySet(), 2))
        add(Scenario("return-to-field", emptySet(), 1))
        add(Scenario("resize", emptySet(), 4))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = ProtocossGame::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(game) as T

    override fun create() {
        val nativeInput = Gdx.input
        nativeGraphics = Gdx.graphics
        fixedInput = object : Input by nativeInput {
            override fun isKeyPressed(key: Int) = key in held
            override fun isKeyJustPressed(key: Int) = key in justPressed
        }
        fixedGraphics = object : Graphics by nativeGraphics {
            override fun getDeltaTime() = 1f / 60f
        }
        Gdx.input = fixedInput
        Gdx.graphics = fixedGraphics
        game.create()
        background = Pixmap(Gdx.files.internal("docs/images/new-game-field.png"))
        sprite = Pixmap(Gdx.files.internal("assets/sprites/kaman_test.png"))
    }

    override fun render() {
        Gdx.input = fixedInput
        Gdx.graphics = fixedGraphics
        val scenario = scenarios[scenarioIndex]
        val player = field<Player>("player")
        val camera = field<OrthographicCamera>("camera")
        held.clear()
        held.addAll(scenario.keys)
        justPressed.clear()
        if (tick == 0) {
            if (scenario.name == "initial") justPressed.add(Input.Keys.ENTER)
            when (scenario.name) {
                "gallery" -> {
                    justPressed.add(Input.Keys.M)
                    player.setPosition(-500f, 500f)
                    camera.position.set(-500f, 500f, 0f)
                }
                "return-to-field" -> justPressed.add(Input.Keys.M)
                "resize" -> check(nativeGraphics.setWindowedMode(1000, 700)) { "창 크기 변경 실패" }
                else -> {
                    player.setPosition(160f, 100f)
                    camera.position.set(160f, 100f, 0f)
                }
            }
        }
        game.render()
        if (scenario.name == "gallery") {
            check(!field<Boolean>("isTileMapMode") && player.x < 0f && player.y > 200f)
        } else {
            check(field<Boolean>("isTileMapMode"))
            check(player.x in 17f..303f && player.y in 22f..178f) { "캐릭터 맵 이탈: ${scenario.name}" }
            check(camera.position.x == 160f && camera.position.y == 100f) { "카메라 맵 이탈: ${scenario.name}" }
        }
        if (++tick == scenario.frames) {
            if (scenario.name in setOf("walk-left", "resize")) saveScreenshot(scenario.name)
            if (scenario.name != "gallery") compareScenePixels(player)
            println("필드 경계 실행 검사: ${scenario.name} 통과")
            tick = 0
            scenarioIndex++
            if (scenarioIndex == scenarios.size) {
                completed = true
                Gdx.files.local("desktop/build/reports/field-bounds/result.txt").writeString(
                    "${scenarios.size}개 이동·전환·크기 변경 시나리오 통과, ${comparedPixels}픽셀 일치\n", false
                )
                println("필드 경계 검사 완료: ${scenarios.size}개 시나리오, ${comparedPixels}픽셀 일치")
                Gdx.app.exit()
            }
        }
    }

    private fun compareScenePixels(player: Player) {
        val width = nativeGraphics.backBufferWidth
        val height = nativeGraphics.backBufferHeight
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, width, height, true)
        val viewport = field<Viewport>("viewport")
        val dpiX = width.toFloat() / nativeGraphics.width
        val dpiY = height.toFloat() / nativeGraphics.height
        val left = viewport.screenX * dpiX
        val top = height - (viewport.screenY + viewport.screenHeight) * dpiY
        val scaleX = viewport.screenWidth * dpiX / 320f
        val scaleY = viewport.screenHeight * dpiY / 200f
        if (scenarios[scenarioIndex].name == "resize") {
            val glViewport = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4)
            Gdx.gl.glGetIntegerv(com.badlogic.gdx.graphics.GL20.GL_VIEWPORT, glViewport)
            println("크기 변경 검사: 창 ${nativeGraphics.width}x${nativeGraphics.height}, 버퍼 ${width}x${height}, 뷰포트 ${viewport.screenX},${viewport.screenY},${viewport.screenWidth},${viewport.screenHeight}, GL ${glViewport[0]},${glViewport[1]},${glViewport[2]},${glViewport[3]}")
        }
        val spriteLeft = (player.x - player.width / 2f).roundToInt()
        val spriteTop = (200f - player.y - player.height / 2f).roundToInt()
        check(abs(player.x - player.x.roundToInt()) < 0.001f)
        check(abs(player.y - player.y.roundToInt()) < 0.001f)
        // 상단 조작 안내를 제외하고 맵과 캐릭터 합성을 비교한다.
        for (y in 30 until 200) {
            for (x in 0 until 320) {
                var expected = background.getPixel(x, y)
                val sx = x - spriteLeft
                val sy = y - spriteTop
                if (sx in 0 until sprite.width && sy in 0 until sprite.height) {
                    val color = sprite.getPixel(sx, sy)
                    if (color and 255 != 0) expected = color
                }
                val px = (left + (x + 0.5f) * scaleX).toInt()
                val py = (top + (y + 0.5f) * scaleY).toInt()
                val index = (py * width + px) * 4
                val actual = ((pixels[index].toInt() and 255) shl 24) or
                    ((pixels[index + 1].toInt() and 255) shl 16) or
                    ((pixels[index + 2].toInt() and 255) shl 8) or (pixels[index + 3].toInt() and 255)
                check(expected == actual) { "배경·캐릭터 픽셀 불일치: ${scenarios[scenarioIndex].name}, ($x,$y), 예상 ${expected.toUInt().toString(16)}, 실제 ${actual.toUInt().toString(16)}, 샘플 ($px,$py)" }
                comparedPixels++
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val width = nativeGraphics.backBufferWidth
        val height = nativeGraphics.backBufferHeight
        val image = Pixmap(width, height, Pixmap.Format.RGBA8888)
        image.pixels.put(ScreenUtils.getFrameBufferPixels(0, 0, width, height, true))
        PixmapIO.writePNG(Gdx.files.local("desktop/build/reports/field-bounds/$name.png"), image)
        image.dispose()
    }

    override fun resize(width: Int, height: Int) = game.resize(width, height)

    override fun dispose() {
        game.dispose()
        background.dispose()
        sprite.dispose()
    }
}
