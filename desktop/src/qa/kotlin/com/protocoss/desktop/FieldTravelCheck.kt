package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.PixmapIO
import com.protocoss.ProtocossGame
import com.protocoss.field.FieldRules
import com.protocoss.player.Player
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/** 원본 폴더 없이 실제 입력으로 벽 충돌·문 왕복·저장 상태를 검사한다. */
object FieldTravelCheck {
    @JvmStatic fun main(args:Array<String>) {
        val directory=Files.createTempDirectory("protocoss-field-travel-qa-")
        try {
            val app=TravelApplication(directory)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("출입구 이동 검사");setWindowedMode(640,400);setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
private class TravelApplication(val saves:Path):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val held=mutableSetOf<Int>()
    private val pressed=mutableSetOf<Int>()
    private lateinit var input:Input
    private lateinit var graphics:Graphics
    private lateinit var rules:FieldRules
    private val compared=mutableSetOf<Int>()
    private var comparedPixels=0
    var completed=false;private set
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String)=ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible=true }.get(game) as T
    private fun load(save:DosSave) { ProtocossGame::class.java.getDeclaredMethod("enterField",DosSave::class.java).apply { isAccessible=true }.invoke(game,save);idle() }
    private fun tick() { Gdx.input=input;Gdx.graphics=graphics;game.render();pressed.clear() }
    private fun idle() { held.clear();repeat(80) { tick() } }
    private fun key(direction:Int)=when(direction) { 1->Input.Keys.W;2->Input.Keys.D;4->Input.Keys.S;else->Input.Keys.A }
    private fun walk(direction:Int,ticks:Int=8) { held.clear();held+=key(direction);repeat(ticks) { tick() };held.clear() }
    override fun create() {
        val nativeAudio=Gdx.audio
        Gdx.audio=object:Audio by nativeAudio { override fun newMusic(file:com.badlogic.gdx.files.FileHandle)=nativeAudio.newMusic(file).apply { volume=0f } }
        val nativeInput=Gdx.input;val nativeGraphics=Gdx.graphics
        input=object:Input by nativeInput { override fun isKeyPressed(key:Int)=key in held;override fun isKeyJustPressed(key:Int)=key in pressed }
        graphics=object:Graphics by nativeGraphics { override fun getDeltaTime()=1f/70f }
        Gdx.input=input;Gdx.graphics=graphics;game.create();game.resize(nativeGraphics.width,nativeGraphics.height)
        rules=field("fieldRules")
    }
    private data class Node(val x:Int,val y:Int,val layer:Int,val doorOpen:Boolean=false)
    private fun route():List<Int> {
        val save=field<DosSave>("session");val p=field<Player>("player")
        val h=if(save.map==0) 400 else 200
        val start=Node((p.x-12).roundToInt(),(h-p.y-12).roundToInt(),save.layer)
        val queue=ArrayDeque<Node>();queue+=start
        val previous=mutableMapOf<Node,Pair<Node,Int>>()
        val visited=mutableSetOf(start)
        while(queue.isNotEmpty()) {
            val node=queue.removeFirst()
            for(d in listOf(1,2,4,8)) {
                val open=node.doorOpen || (save.map==0 && rules.event(0,node.x.toFloat(),node.y.toFloat(),d,node.doorOpen)==0)
                val ruleMap=save.map
                if(rules.event(ruleMap,node.x.toFloat(),node.y.toFloat(),d,open)==1) {
                    val result=mutableListOf(d);var cursor=node
                    while(cursor!=start) { val (parent,dir)=previous.getValue(cursor);result+=dir;cursor=parent }
                    return result.reversed()
                }
                if(rules.event(ruleMap,node.x.toFloat(),node.y.toFloat(),d,open)==16) continue
                val step=rules.step(ruleMap,node.x.toFloat(),node.y.toFloat(),d,node.layer,open)
                if(step.direction==0) continue
                val next=Node(node.x+FieldRules.dx(step.direction)*8,node.y+FieldRules.dy(step.direction)*8,step.layer,open)
                if(visited.add(next)) { previous[next]=node to d;queue+=next }
            }
        }
        error("문까지 갈 수 없습니다: 맵 ${save.map}")
    }
    private fun capture(name:String) {
        val directory=Path.of("desktop/build/reports/field-travel");Files.createDirectories(directory)
        val image=com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.absolute(directory.resolve("$name.png").toAbsolutePath().toString()),image,-1,true) } finally { image.dispose() }
    }
    private fun compareNative(id:Int) {
        val expected=com.badlogic.gdx.graphics.Pixmap(Gdx.files.internal("assets/opening/reference-$id.png"))
        val pixels=com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight,true)
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
    private fun completeEvent(program:Int) {
        val timeline=field<com.protocoss.opening.OpeningTimeline>("opening")
        check(timeline.program==program)
        var checkpoints=0;var previous=-1
        repeat(10000) {
            if(field<Any>("scene").toString()=="FIELD") { check(checkpoints>0);idle();return }
            val page=timeline.page
            if(page!=null) {
                if(previous!=timeline.dialogueId) { capture("event-$program-dialogue-${timeline.dialogueId}");checkpoints++;previous=timeline.dialogueId!! }
                val state=timeline.actors.map { it.x to it.y }
                held+=Input.Keys.D;repeat(80) { tick() };held.clear()
                check(state==timeline.actors.map { it.x to it.y })
                val id=timeline.dialogueId!!
                if(timeline.pageIndex==0 && compared.add(id)) compareNative(id)
                capture("event-$program-dialogue-$id-${timeline.pageIndex}")
                pressed+=Input.Keys.ENTER
            }
            tick()
        }
        error("필드 연출 종료 실패")
    }
    override fun render() {
        if(completed) return
        val start=DosSave.afterOpening(Gdx.files.internal("assets/data/original/start.dat").readBytes())
        load(start)
        val player=field<Player>("player")
        val position=player.x to player.y
        walk(1,70);check(position==player.x to player.y) { "침대 벽을 통과했습니다." }
        held+=Input.Keys.W;held+=Input.Keys.D;held+=Input.Keys.SHIFT_LEFT;repeat(70) { tick() };held.clear()
        check(position==player.x to player.y) { "대각선으로 침대를 통과했습니다." }
        walk(8,1)
        check(field<Any>("scene").toString()=="OPENING")
        completeEvent(2)
        val awake=field<DosSave>("session")
        check(awake.map==1 && awake.variable(6)==1 && awake.variable(1)==17)
        check(player.x==68f && player.y==148f)
        capture("awake")
        load(awake)
        var steps=0
        for(stage in listOf(0,2,3)) {
            if(stage>0) {
                val state=start.travel(1,0,0,160,184,0,0,mapOf(0 to 0,8 to stage)).bytes()
                if(stage==2) state[500]=1
                load(DosSave.parse(state))
            }
            val path=route();path.forEach { walk(it);steps++ };idle()
            val outside=field<DosSave>("session")
            check(outside.map==0 && outside.variable(0)==1 && outside.layer==1)
            capture("yard-stage-$stage")
            val back=route();back.forEach {
                walk(it);steps++
                if(field<com.protocoss.field.FieldMovement>("fieldMovement").doorOpen)
                    check(field<DosSave>("session").variable(1)==0)
            }
            if(stage==2) completeEvent(3)
            idle()
            val inside=field<DosSave>("session")
            if(stage==2) check(inside.bytes()[500]==0.toByte())
            check(inside.map==if(stage==0) 2 else 1);check(inside.variable(0)==0 && inside.variable(8)==if(stage==2) 3 else stage)
            check(field<Any>("scene").toString()=="FIELD")
            capture("cabin-stage-$stage")
            // 실제 저장 메뉴로 기록하고 이어서 불러온다.
            pressed+=Input.Keys.ESCAPE;tick();pressed+=Input.Keys.DOWN;tick();pressed+=Input.Keys.ENTER;tick()
            pressed+=Input.Keys.ENTER;tick()
            if(field<Any>("scene").toString()=="CONFIRM_SAVE") { pressed+=Input.Keys.DOWN;tick();pressed+=Input.Keys.ENTER;tick() }
            check(Files.exists(saves.resolve("SAVE0.DAT")))
            val bytes=Files.readAllBytes(saves.resolve("SAVE0.DAT"));check(DosSave.parse(bytes).variable(8)==if(stage==2) 3 else stage)
            pressed+=Input.Keys.DOWN;tick();pressed+=Input.Keys.DOWN;tick();pressed+=Input.Keys.ENTER;tick();pressed+=Input.Keys.ENTER;tick();idle()
            check(field<DosSave>("session").map==inside.map)
            check(field<DosSave>("session").variable(8)==if(stage==2) 3 else stage)
        }
        println("필드 연출 원작 화면 $comparedPixels 픽셀 일치")
        println("필드 이동 실행 검사 통과: 원본 폴더 없이 $steps 걸음, 벽·대각선·초기/진행 후 문 왕복·저장/불러오기")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
