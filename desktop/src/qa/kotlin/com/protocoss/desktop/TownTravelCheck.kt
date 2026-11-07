package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import com.protocoss.ProtocossGame
import com.protocoss.field.*
import com.protocoss.opening.OpeningTimeline
import com.protocoss.player.Player
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/** 원본 폴더 없이 오두막→마당→검은 망토 영상→마을과 재진입을 실행한다. */
object TownTravelCheck {
    @JvmStatic fun main(args:Array<String>) {
        val saves=Files.createTempDirectory("protocoss-town-qa-")
        try {
            val app=TownApplication(saves)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("마을 진입 자동 검사");setWindowedMode(640,400);setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
private class TownApplication(private val saves:Path):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val held=mutableSetOf<Int>();private val pressed=mutableSetOf<Int>()
    private lateinit var input:Input;private lateinit var graphics:Graphics;private lateinit var rules:FieldRules
    private var walked=0;private var pixels=0;private val movieFrames=mutableSetOf<Int>()
    var completed=false;private set
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String)=ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible=true }.get(game) as T
    private fun scene()=field<Any>("scene").toString()
    private fun tick() { Gdx.input=input;Gdx.graphics=graphics;game.render();pressed.clear() }
    private fun press(key:Int) { pressed+=key;tick() }
    private fun idle(frames:Int=80) { held.clear();repeat(frames) { tick() } }
    private fun session()=field<DosSave>("session")
    private fun load(save:DosSave) { ProtocossGame::class.java.getDeclaredMethod("enterField",DosSave::class.java).apply { isAccessible=true }.invoke(game,save);idle() }
    private fun capture(name:String) {
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("desktop/build/reports/town-travel/$name.png"),image,-1,true) } finally { image.dispose() }
    }
    override fun create() {
        val audio=Gdx.audio;Gdx.audio=object:Audio by audio { override fun newMusic(file:com.badlogic.gdx.files.FileHandle)=audio.newMusic(file).apply { volume=0f } }
        val nativeInput=Gdx.input;val nativeGraphics=Gdx.graphics
        input=object:Input by nativeInput { override fun isKeyPressed(key:Int)=key in held;override fun isKeyJustPressed(key:Int)=if(key==Input.Keys.ANY_KEY) pressed.isNotEmpty() else key in pressed }
        graphics=object:Graphics by nativeGraphics { override fun getDeltaTime()=1f/70f }
        Gdx.input=input;Gdx.graphics=graphics;game.create();game.resize(nativeGraphics.width,nativeGraphics.height);rules=field("fieldRules")
    }
    private data class Node(val x:Int,val y:Int,val layer:Int,val open:Boolean=false)
    private fun route(event:Int) {
        val map=session().map;val height=field<com.protocoss.map.TileMap>("tileMap").totalPixelHeight
        val player=field<Player>("player");val movement=field<FieldMovement>("fieldMovement")
        val start=Node((player.x-12).roundToInt(),(height-player.y-12).roundToInt(),movement.layer,movement.doorOpen)
        val queue=ArrayDeque<Node>();queue+=start;val visited=mutableSetOf(start);val previous=mutableMapOf<Node,Pair<Node,Int>>()
        var path:List<Int>?=null
        search@while(queue.isNotEmpty()) {
            val node=queue.removeFirst()
            for(d in listOf(1,2,4,8)) {
                val opened=node.open || (map==0 && rules.event(map,node.x.toFloat(),node.y.toFloat(),d,node.open)==0)
                val trigger=rules.event(map,node.x.toFloat(),node.y.toFloat(),d,opened)
                if(trigger==event) {
                    val result=mutableListOf(d);var cursor=node
                    while(cursor!=start) { val (parent,direction)=previous.getValue(cursor);result+=direction;cursor=parent }
                    path=result.reversed();break@search
                }
                if(trigger!=null && !(map==0 && trigger==0)) continue
                val step=rules.step(map,node.x.toFloat(),node.y.toFloat(),d,node.layer,opened)
                if(step.direction==0) continue
                val next=Node(node.x+FieldRules.dx(step.direction)*8,node.y+FieldRules.dy(step.direction)*8,step.layer,opened)
                if(visited.add(next)) { previous[next]=node to d;queue+=next }
            }
        }
        check(path!=null) { "출구 접근 실패: 맵 $map, 이벤트 $event, $start" }
        for(d in path) {
            held.clear();held+=when(d) { 1->Input.Keys.W;2->Input.Keys.D;4->Input.Keys.S;else->Input.Keys.A }
            repeat(8) { tick() };held.clear();walked++
        }
        idle()
    }
    private fun compare(imagePath:String,exclude:(Int,Int)->Boolean={_,_->false}) {
        val expected=Pixmap(Gdx.files.internal(imagePath));val buffer=ScreenUtils.getFrameBufferPixels(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight,true)
        try {
            // 창은 640×400, Retina 배율도 실제 버퍼에서 계산한다.
            val sx=Gdx.graphics.backBufferWidth/320f;val sy=Gdx.graphics.backBufferHeight/200f
            for(y in 0 until 200) for(x in 0 until 320) {
                if(exclude(x,y)) continue
                val offset=(((y+.5f)*sy).toInt()*Gdx.graphics.backBufferWidth+((x+.5f)*sx).toInt())*4
                val rgba=((buffer[offset].toInt()and 255)shl 24) or ((buffer[offset+1].toInt()and 255)shl 16) or ((buffer[offset+2].toInt()and 255)shl 8) or (buffer[offset+3].toInt()and 255)
                check(rgba==expected.getPixel(x,y)) { "마을·조우 화면 불일치 $imagePath ($x,$y)" };pixels++
            }
        } finally { expected.dispose() }
    }
    private fun finishAnimation(movie:Boolean=false) {
        val timeline=field<OpeningTimeline>("opening");var sawMovie=false;var frames=0
        repeat(12000) {
            if(scene()=="FIELD") {
                check(!movie || sawMovie);idle();return
            }
            check(scene() in setOf("OPENING","CLIP"))
            if(scene()=="CLIP") {
                sawMovie=true
                val clip=field<com.protocoss.prologue.ProloguePlayer>("encounterClip")
                val index=clip.timeline.stream.frameIndex
                if(field<Boolean>("clipStarted") && !field<Boolean>("clipReturning") && index in setOf(0,12,24) && movieFrames.add(index)) {
                    check(!field<com.badlogic.gdx.audio.Music>("openingMusic").isPlaying)
                    val music=com.protocoss.prologue.ProloguePlayer::class.java.getDeclaredField("music").apply { isAccessible=true }.get(clip) as com.badlogic.gdx.audio.Music
                    check(music.volume==0f && music.isPlaying)
                    if(index==12) {
                        game.pause();check(!music.isPlaying);game.resume();check(music.isPlaying)
                        check(!field<com.badlogic.gdx.audio.Music>("openingMusic").isPlaying)
                    }
                    compare("assets/encounter/reference-$index.png");capture("eyes-$index")
                }
                held+=Input.Keys.D
            } else {
                held.clear()
                if(timeline.page!=null) {
                    idle(80)
                    if(timeline.dialogueId==203 && timeline.pageIndex==0 && timeline.pageComplete) {
                        compare("assets/opening/reference-203.png");capture("forest-dialogue")
                    }
                    pressed+=Input.Keys.ENTER
                }
            }
            tick();frames++
        }
        error("연출 종료 실패: $frames")
    }
    private fun finishBanner() {
        idle()
        val banner=field<PlaceBanner?>("placeBanner") ?: return
        check(banner.waitingForInput)
        val player=field<Player>("player");val position=player.x to player.y
        held+=Input.Keys.D;repeat(140) { tick() };held.clear()
        check(position==(player.x to player.y));check(banner.waitingForInput)
        capture("place-${session().map}")
        press(Input.Keys.ENTER);idle(40);check(field<PlaceBanner?>("placeBanner")==null)
    }
    private fun saveAndLoad() {
        val before=session();val player=field<Player>("player");val position=player.x to player.y
        press(Input.Keys.ESCAPE);press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        if(scene()=="CONFIRM_SAVE") { press(Input.Keys.DOWN);press(Input.Keys.ENTER) }
        check(scene()=="PAUSE")
        press(Input.Keys.DOWN);press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER);idle()
        check(scene()=="FIELD" && session().map==before.map && session().variable(7)==1)
        check(position==(player.x to player.y))
        check(field<com.badlogic.gdx.audio.Music>("openingMusic").isLooping)
    }
    override fun render() {
        if(completed) return
        press(Input.Keys.ENTER);press(Input.Keys.SPACE);finishAnimation()
        held+=Input.Keys.A;tick();held.clear();finishAnimation();check(session().variable(6)==1)
        route(1);check(session().map==0)
        route(2);check(session().map==3 && session().variable(7)==0)
        route(0);check(session().map==0 && session().variable(7)==0)
        route(2);check(session().map==3)
        route(2);check(scene() in setOf("OPENING","CLIP"));finishAnimation(true)
        check(session().map==3 && session().variable(7)==1 && session().variable(1)==3)
        check(movieFrames==setOf(0,12,24))
        route(1);check(session().map==4);finishBanner()
        val maps=field<OpeningTimeline>("opening").data.get("maps")
        fun compareTown(map:Int) {
            val animations=maps.get(map.toString()).get("animations")
            compare("assets/opening/reference-town-$map.png") { x,y -> animations.any { rect ->
                x/8 in rect[0].asInt() until rect[0].asInt()+rect[2].asInt() && y/8 in rect[1].asInt() until rect[1].asInt()+rect[3].asInt()
            } }
            capture("town-$map")
        }
        compareTown(4);saveAndLoad()
        route(1);check(session().map==0);finishBanner()
        route(2);check(scene()=="FIELD" && session().map==4);finishBanner()
        for(stage in listOf(2,3)) {
            val later=session().travel(0,40,25,304,184,12,0,mapOf(0 to 1,8 to stage));load(later)
            route(2);check(scene()=="FIELD" && session().map==if(stage<3) 4 else 12)
            check(session().variable(8)==stage);finishBanner();compareTown(if(stage<3) 4 else 12);saveAndLoad()
            route(1);check(session().map==0);finishBanner()
        }
        println("마을 실행 검사 통과: $walked 걸음, 조우 1회·25프레임 영상·마을 4/12 진입·복귀·저장 재개, $pixels 픽셀 대조")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
