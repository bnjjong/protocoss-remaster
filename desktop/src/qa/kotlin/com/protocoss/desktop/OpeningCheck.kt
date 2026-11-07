package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import com.protocoss.ProtocossGame
import com.protocoss.opening.OpeningTimeline
import com.protocoss.player.Player
import java.nio.file.Files
import java.nio.file.Path

/** 새 게임의 연출·자동 이동·대화 대기·첫 조작을 실제 그래픽 환경에서 검사한다. */
object OpeningCheck {
    @JvmStatic fun main(args: Array<String>) {
        val saves=Files.createTempDirectory("protocoss-opening-qa-")
        try {
            val app=OpeningApplication(saves)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("시작 연출 자동 검사");setWindowedMode(640,400);setInitialVisible(false)
                useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
private class OpeningApplication(saves: Path): ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val pressed=mutableSetOf<Int>()
    private lateinit var controlledInput: Input
    private lateinit var controlledGraphics: Graphics
    private var delta=0f
    private var tick=0
    private var stage=0
    private val compared=mutableSetOf<Int>()
    private var comparedPixels=0
    private val captured=mutableSetOf<String>()
    var completed=false;private set
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name:String)=ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible=true }.get(game) as T
    private fun scene()=field<Any>("scene").toString()
    override fun create() {
        val audio=Gdx.audio
        Gdx.audio=object:Audio by audio { override fun newMusic(file:com.badlogic.gdx.files.FileHandle)=audio.newMusic(file).apply { volume=0f } }
        val input=Gdx.input;val graphics=Gdx.graphics
        controlledInput=object:Input by input {
            override fun isKeyJustPressed(key:Int)=key in pressed
            override fun isKeyPressed(key:Int)=key==Input.Keys.D
        }
        controlledGraphics=object:Graphics by graphics { override fun getDeltaTime()=delta }
        Gdx.input=controlledInput;Gdx.graphics=controlledGraphics
        game.create();game.resize(graphics.width,graphics.height)
    }
    private fun capture(name:String) {
        if(!captured.add(name)) return
        val directory=Path.of("desktop/build/reports/opening");Files.createDirectories(directory)
        val image=com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.absolute(directory.resolve("$name.png").toAbsolutePath().toString()),image,-1,true) }
        finally { image.dispose() }
    }
    private fun compareNative(id:Int) {
        val expected=com.badlogic.gdx.graphics.Pixmap(Gdx.files.internal("assets/opening/reference-$id.png"))
        val pixels=ScreenUtils.getFrameBufferPixels(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight,true)
        val viewport=field<com.badlogic.gdx.utils.viewport.Viewport>("viewport")
        val dpiX=Gdx.graphics.backBufferWidth.toFloat()/Gdx.graphics.width
        val dpiY=Gdx.graphics.backBufferHeight.toFloat()/Gdx.graphics.height
        val left=viewport.screenX*dpiX;val top=(Gdx.graphics.height-viewport.screenY-viewport.screenHeight)*dpiY
        val scaleX=viewport.screenWidth*dpiX/320f;val scaleY=viewport.screenHeight*dpiY/200f
        try {
            for(y in 0 until 200) for(x in 0 until 320) {
                // 불꽃은 원작과 현재 실행의 타이머 위상이 다르다. 인물·가림·대화·글꼴은 모두 비교한다.
                if(x in 200 until 224 && y in 32 until 48) continue
                val offset=(((top+(y+.5f)*scaleY).toInt()*Gdx.graphics.backBufferWidth)+(left+(x+.5f)*scaleX).toInt())*4
                val rgba=((pixels[offset].toInt()and 255) shl 24) or ((pixels[offset+1].toInt()and 255)shl 16) or ((pixels[offset+2].toInt()and 255)shl 8) or (pixels[offset+3].toInt()and 255)
                check(rgba==expected.getPixel(x,y)) { "원작 시작 장면 픽셀 불일치: 대화 $id, ($x,$y), 예상 ${expected.getPixel(x,y).toUInt().toString(16)}, 실제 ${rgba.toUInt().toString(16)}" }
                comparedPixels++
            }
        } finally { expected.dispose() }
    }
    override fun render() {
        Gdx.input=controlledInput;Gdx.graphics=controlledGraphics
        pressed.clear();delta=1f/70f
        when(stage) {
            0 -> { pressed+=Input.Keys.ENTER;stage++ }
            1 -> { check(scene()=="PROLOGUE");pressed+=Input.Keys.SPACE;stage++ }
            2 -> {
                if(scene()=="FIELD") { stage=3;return }
                check(scene()=="OPENING")
                val opening=field<OpeningTimeline>("opening")
                if(opening.program==0 && tick%100==0) capture("exterior-${tick/100}")
                val page=opening.page
                if(page!=null) {
                    // 길게 기다리거나 이동 입력을 눌러도 대화가 자동으로 넘어가지 않는다.
                    val id=opening.dialogueId;val pageIndex=opening.pageIndex
                    val positions=opening.actors.map { it.x to it.y }
                    repeat(3) { delta=1f;game.render();check(opening.dialogueId==id && opening.pageIndex==pageIndex);check(positions==opening.actors.map { it.x to it.y }) }
                    capture("dialogue-$id-$pageIndex")
                    if(pageIndex==0 && compared.add(id!!)) {
                        compareNative(id)
                        val song=field<com.badlogic.gdx.audio.Music>("openingMusic")
                        check(song.volume==0f && song.isLooping && song.isPlaying)
                        game.pause();check(!song.isPlaying);game.resume();check(song.isPlaying)
                    }
                    pressed+=Input.Keys.ENTER
                }
                tick++
                if(opening.finished) stage++
                check(tick<10000) { "시작 연출이 종료되지 않았습니다." }
            }
            3 -> {
                check(scene()=="FIELD")
                if(field<Float>("fieldFade")>0f) { game.render();return }
                val player=field<Player>("player");val x=player.x
                game.render();check(player.x>x);capture("first-control")
                check(field<com.badlogic.gdx.audio.Music>("openingMusic").isPlaying)
                pressed.clear();pressed+=Input.Keys.ESCAPE;game.render()
                repeat(3) { pressed.clear();pressed+=Input.Keys.DOWN;game.render() }
                pressed.clear();pressed+=Input.Keys.ENTER;game.render()
                pressed.clear();pressed+=Input.Keys.DOWN;game.render()
                pressed.clear();pressed+=Input.Keys.ENTER;game.render()
                check(scene()=="TITLE" && field<Any?>("openingMusic")==null)
                println("시작 연출: $tick 프레임 진행, ${captured.size}장 저장, 대화 중 이동·자동 넘김 차단, 첫 자유 이동 확인, 원작 기준 $comparedPixels 픽셀 일치")
                completed=true;Gdx.app.exit();return
            }
        }
        game.render()
    }
    override fun dispose() { game.dispose() }
}
