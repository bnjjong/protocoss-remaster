package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import com.protocoss.ProtocossGame
import com.protocoss.map.OriginalMapData
import com.protocoss.map.TileMap
import com.protocoss.player.Player
import com.protocoss.save.DosSave
import com.protocoss.save.SaveRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.math.abs

/** 사용자 저장과 분리한 폴더에서 메뉴·DOS 저장·앱 재시작을 실제 그래픽으로 검사한다. */
object TitleSaveCheck {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("protocoss-title-qa-")
        try {
            val app = MenuCheck(directory)
            Lwjgl3Application(app, Lwjgl3ApplicationConfiguration().apply {
                setTitle("시작 메뉴 자동 검사")
                setWindowedMode(640, 400)
                setInitialVisible(false)
                useVsync(false)
                setForegroundFPS(0)
            })
            check(app.completed)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}

private class MenuCheck(private val directory: Path) : ApplicationAdapter() {
    private val source = requireNotNull(ProtocossGame.defaultOriginalDirectory()) { "원본 경로가 필요합니다." }
    private var game = ProtocossGame(source, directory)
    private lateinit var input: Input
    private lateinit var graphics: Graphics
    private val held = mutableSetOf<Int>()
    private val pressed = mutableSetOf<Int>()
    private var clicked = false
    private var mouseX = 0
    private var mouseY = 0
    private var step = 0
    private var frames = 0
    private var savedX = 0f
    private var pixelsCompared = 0
    private val hashes = (0..3).map { hash(Files.readAllBytes(source.resolve("DATA/SAVE$it.DAT"))) }
    var completed = false
        private set
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String) = ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible = true }.get(game) as T
    private fun scene() = field<Any>("scene").toString()
    private fun key(key: Int) { pressed.add(key) }
    private fun enter() = key(Input.Keys.ENTER)
    private fun down() = key(Input.Keys.DOWN)
    private fun escape() = key(Input.Keys.ESCAPE)
    private fun checkScene(expected: String) = check(scene() == expected) { "단계 $step: $expected 대신 ${scene()}" }

    override fun create() {
        // 자동 검사는 시스템 음량과 다른 앱을 건드리지 않고 이 앱의 음악만 음소거한다.
        val nativeAudio = Gdx.audio
        Gdx.audio = object : com.badlogic.gdx.Audio by nativeAudio {
            override fun newMusic(file: com.badlogic.gdx.files.FileHandle) = nativeAudio.newMusic(file).apply { volume = 0f }
        }
        val nativeInput = Gdx.input
        val nativeGraphics = Gdx.graphics
        input = object : Input by nativeInput {
            override fun isKeyPressed(key: Int) = key in held
            override fun isKeyJustPressed(key: Int) = key in pressed
            override fun justTouched() = clicked
            override fun isButtonPressed(button: Int) = clicked && button == Input.Buttons.LEFT
            override fun getX() = mouseX
            override fun getY() = mouseY
        }
        graphics = object : Graphics by nativeGraphics { override fun getDeltaTime() = 1f / 60f }
        Gdx.input = input
        Gdx.graphics = graphics
        game.create()
    }

    override fun render() {
        Gdx.input = input
        Gdx.graphics = graphics
        pressed.clear(); held.clear(); clicked = false
        when (step) {
            1 -> down()
            2 -> enter()
            3 -> enter() // 빈 슬롯
            4 -> escape()
            5 -> { // 실제 마우스로 NEW START 선택
                clicked = true; mouseX = 300; mouseY = 310
            }
            6 -> escape()
            7 -> down()
            8 -> down()
            9 -> enter()
            10, 11, 12, 13 -> down()
            14 -> enter() // DOS 1
            15 -> { held.add(Input.Keys.D); if (++frames < 12) { game.render(); return } }
            16 -> escape()
            17 -> down()
            18 -> enter()
            19 -> enter() // 저장 1
            20 -> enter() // 계속
            21 -> { // 새 애플리케이션 인스턴스로 저장 지속성 확인
                game.dispose()
                game = ProtocossGame(source, directory)
                game.create()
                game.resize(graphics.width, graphics.height)
            }
            22 -> down()
            23 -> enter()
            24 -> enter() // 리마스터 저장 1
            25 -> escape()
            26, 27, 28 -> down()
            29 -> enter() // 메인 복귀 확인
            30 -> down()
            31 -> enter()
            32 -> enter() // 새 게임
            33 -> { // 메뉴의 대화 중 저장 방지 검사는 대화 상태를 직접 준비한다.
                field<com.protocoss.dialogue.DialogueManager>("dialogueManager").startDialogue(
                    field<com.protocoss.dialogue.DialogueRepository>("dialogueRepository").getSegment(0)!!)
            }
            34 -> escape()
            35 -> down()
            36 -> enter() // 대화 중 저장 방지
            37 -> escape()
            38 -> key(Input.Keys.SPACE)
            39 -> escape()
            40 -> down()
            41, 42, 43, 44 -> enter()
            45 -> down()
            46 -> enter()
            47, 48 -> down()
            49 -> { Files.write(directory.resolve("SAVE2.DAT"), byteArrayOf(1, 2, 3)); enter() }
            50, 51 -> down()
            52 -> enter()
            53 -> escape()
            54, 55, 56 -> down()
            57 -> enter()
            58 -> down()
            59 -> { enter(); frames = 0 }
            60 -> if (frames == 0) check(graphics.setWindowedMode(1000, 700))
            61 -> {
                val viewport = field<com.badlogic.gdx.utils.viewport.Viewport>("viewport")
                mouseX = (viewport.screenX + 150f * viewport.screenWidth / 320f).toInt()
                mouseY = (graphics.height - viewport.screenY - 45f * viewport.screenHeight / 200f).toInt()
                clicked = true
            }
            62 -> { // 잘못된 DOS 슬롯은 목록에서 오류로 보인다. 사용자 원본은 바꾸지 않는다.
                Files.write(directory.resolve("SAVE2.DAT"), byteArrayOf(1, 2, 3))
                check(SaveRepository(directory, source).localEntries()[2].error != null)
                verifyAllMaps()
                for (i in 0..3) check(hashes[i] == hash(Files.readAllBytes(source.resolve("DATA/SAVE$i.DAT"))))
                completed = true
                Files.createDirectories(Path.of("desktop/build/reports/title-save"))
                Files.writeString(Path.of("desktop/build/reports/title-save/result.txt"), "63개 메뉴 단계 통과, $pixelsCompared 화면 픽셀 일치, 원본 저장 4개 해시 보존\n")
                println("시작 메뉴 검사 완료: 63단계, $pixelsCompared 픽셀 일치, 원본 저장 보존")
                Gdx.app.exit()
                return
            }
        }
        game.render()
        if (scene() == "PROLOGUE") {
            check(step in listOf(5, 32, 61)) { "불러오기가 프롤로그로 들어갔습니다." }
            pressed.clear()
            pressed.add(Input.Keys.ESCAPE)
            game.render()
        }
        if (scene() == "OPENING") {
            // 시작 연출 자체는 verifyOpening에서 검사한다. 여기서는 메뉴·저장 경로를 검사한다.
            pressed.clear()
            val opening=field<com.protocoss.opening.OpeningTimeline>("opening")
            repeat(10000) {
                if(!opening.finished) { opening.update(.2f); if(opening.page!=null) { opening.advance();opening.advance() } }
            }
            check(opening.finished);game.render();repeat(70) { game.render() }
        }
        when (step) {
            0 -> { checkScene("TITLE"); compareTitle(0); capture("title") }
            1 -> { checkScene("TITLE"); compareTitle(1) }
            2, 3 -> { checkScene("LOAD"); check(field<List<*>>("entries").size == 8); if (step == 2) capture("load") }
            4 -> checkScene("TITLE")
            5 -> { checkScene("FIELD"); check(field<DosSave>("session").map == 2); compareField("new-game-field"); capture("new-game") }
            9 -> checkScene("LOAD")
            14 -> {
                checkScene("FIELD")
                check(field<DosSave>("session").map == 16)
                val player = field<Player>("player")
                check(player.x == 156f && player.y == 92f)
                compareField("dos-save-field"); capture("dos-loaded")
            }
            15 -> { savedX = field<Player>("player").x; check(savedX > 156f) }
            18 -> checkScene("SAVE")
            19 -> {
                checkScene("PAUSE")
                val save = DosSave.parse(Files.readAllBytes(directory.resolve("SAVE0.DAT")))
                check(abs(save.worldX + 12f - savedX) <= 0.5f)
                val original = Files.readAllBytes(source.resolve("DATA/SAVE0.DAT"))
                check(original.copyOfRange(119, 2942).contentEquals(save.bytes().copyOfRange(119, 2942)))
                val time = Files.getLastModifiedTime(directory.resolve("SAVE0.DAT")).toInstant().atZone(ZoneId.systemDefault()).toLocalTime()
                check(save.forTime(time).contentEquals(save.bytes()))
                // 재현용 저장은 검사 보고서에만 보관한다.
                Files.createDirectories(Path.of("desktop/build/reports/title-save"))
                Files.copy(directory.resolve("SAVE0.DAT"), Path.of("desktop/build/reports/title-save/SAVE0.DAT"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Files.setLastModifiedTime(Path.of("desktop/build/reports/title-save/SAVE0.DAT"), Files.getLastModifiedTime(directory.resolve("SAVE0.DAT")))
            }
            21 -> checkScene("TITLE")
            24 -> { checkScene("FIELD"); check(abs(field<Player>("player").x - savedX) <= 0.5f); check(field<DosSave>("session").map == 16) }
            29 -> checkScene("CONFIRM_TITLE")
            31 -> { checkScene("TITLE"); compareTitle(0) }
            32 -> { checkScene("FIELD"); check(field<DosSave>("session").map == 2); check(field<Player>("player").x == 92f) }
            33 -> check(field<com.protocoss.dialogue.DialogueManager>("dialogueManager").isActive)
            36 -> { checkScene("PAUSE"); check(field<String>("message").contains("대화를 마치고")) }
            38 -> { if (field<com.protocoss.dialogue.DialogueManager>("dialogueManager").isActive) return }
            42, 44 -> checkScene("CONFIRM_SAVE")
            43 -> checkScene("SAVE")
            46 -> checkScene("PAUSE")
            52 -> { checkScene("LOAD"); check(field<String>("message").contains("읽을 수 없는")) }
            59 -> checkScene("TITLE")
            60 -> { if (++frames < 4) return; checkScene("TITLE"); compareTitle(0); capture("title-resize") }
            61 -> checkScene("FIELD")
        }
        println("메뉴 실행 검사 $step: ${scene()} 통과")
        step++
    }

    private fun compareTitle(selection: Int) {
        val expected = Pixmap(Gdx.files.internal("assets/ui/title/${if (selection == 0) "new-game" else "continue"}.png"))
        val actual = ScreenUtils.getFrameBufferPixels(0, 0, graphics.backBufferWidth, graphics.backBufferHeight, true)
        val viewport = field<com.badlogic.gdx.utils.viewport.Viewport>("viewport")
        val dpiX = graphics.backBufferWidth.toFloat() / graphics.width
        val dpiY = graphics.backBufferHeight.toFloat() / graphics.height
        val left = viewport.screenX * dpiX
        val top = graphics.backBufferHeight - (viewport.screenY + viewport.screenHeight) * dpiY
        val scaleX = viewport.screenWidth * dpiX / 320f
        val scaleY = viewport.screenHeight * dpiY / 200f
        for (y in 0 until 200) for (x in 0 until 320) {
            val px = (left + (x + 0.5f) * scaleX).toInt()
            val py = (top + (y + 0.5f) * scaleY).toInt()
            val offset = (py * graphics.backBufferWidth + px) * 4
            val rgba = rgba(actual, offset)
            check(rgba == expected.getPixel(x, y)) { "타이틀 픽셀 불일치 $x,$y" }
            pixelsCompared++
        }
        expected.dispose()
    }

    private fun compareField(name: String) {
        val background = Pixmap(Gdx.files.internal("docs/images/$name.png"))
        val actual = ScreenUtils.getFrameBufferPixels(0, 0, graphics.backBufferWidth, graphics.backBufferHeight, true)
        val player = field<Player>("player")
        val camera = field<OrthographicCamera>("camera")
        val left = camera.position.x - 160
        val top = background.height - camera.position.y - 100
        val scale = graphics.backBufferWidth / 320
        for (y in 30 until 200) for (x in 0 until 320) {
            if(name=="new-game-field" && x in 200 until 224 && y in 32 until 48) continue
            val worldX = left + x
            val worldY = background.height - top - y
            if (worldX >= player.x - 18 && worldX <= player.x + 18 && worldY >= player.y - 23 && worldY <= player.y + 23) continue
            if(name == "new-game-field" && worldX in 16f..56f && (top+y) in 32f..80f) continue // 벨슨그와 침대의 가림
            val offset = ((y * scale + scale / 2) * graphics.backBufferWidth + x * scale + scale / 2) * 4
            check(rgba(actual, offset) == background.getPixel(worldX.toInt(), (top + y).toInt())) { "필드 픽셀 불일치: $name $x,$y" }
            pixelsCompared++
        }
        background.dispose()
    }

    private fun capture(name: String) {
        val image = Pixmap(graphics.backBufferWidth, graphics.backBufferHeight, Pixmap.Format.RGBA8888)
        image.pixels.put(ScreenUtils.getFrameBufferPixels(0, 0, image.width, image.height, true)).flip()
        PixmapIO.writePNG(Gdx.files.local("desktop/build/reports/title-save/$name.png"), image)
        image.dispose()
    }

    private fun verifyAllMaps() {
        var count = 0
        for (area in 0..5) {
            val bytes = Files.readAllBytes(source.resolve("MAP/AREA%02d.MDA".format(area)))
            val n = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            for (map in 0 until n) { OriginalMapData.load(source, area, map); count++ }
        }
        check(count == 364)
        println("원본 $count 개 맵 구조 로딩 통과")
    }
    private fun rgba(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 255) shl 24) or
        ((bytes[offset + 1].toInt() and 255) shl 16) or ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
    override fun resize(width: Int, height: Int) = game.resize(width, height)
    override fun dispose() = game.dispose()
}
