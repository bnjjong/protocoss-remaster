package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.protocoss.ProtocossGame
import com.protocoss.field.*
import com.protocoss.opening.OpeningRenderer
import com.protocoss.opening.OpeningTimeline
import com.protocoss.player.Player
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt

/** 원본 폴더 없는 마을 건물 왕복·문 상태·내부 통로·저장을 실제 입력으로 확인한다. */
object BuildingCheck {
    @JvmStatic fun main(args:Array<String>) {
        val saves=Files.createTempDirectory("protocoss-building-qa-")
        try {
            val app=BuildingApplication(saves)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("건물 출입 자동 검사");setWindowedMode(640,400);setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
private class BuildingApplication(private val saves:Path):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val held=mutableSetOf<Int>();private val pressed=mutableSetOf<Int>()
    private lateinit var input:Input;private lateinit var graphics:Graphics;private lateinit var rules:FieldRules
    private var delta=1f/70f;private var steps=0;private var effects=0
    private val captures=JsonValue(JsonValue.ValueType.array)
    private val music=mutableListOf<com.badlogic.gdx.audio.Music>()
    var completed=false;private set
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String)=ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible=true }.get(game) as T
    private fun tick() {
        Gdx.input=input;Gdx.graphics=graphics;game.render();pressed.clear()
        check(music.count { it.isPlaying }<=1) { "실내·마을 음악이 겹쳐 재생됩니다." }
    }
    private fun press(key:Int) { pressed+=key;tick() }
    private fun idle(frames:Int=80) { repeat(frames) { tick() } }
    private fun session()=field<DosSave>("session")
    private fun scene()=field<Any>("scene").toString()
    private fun load(save:DosSave) { ProtocossGame::class.java.getDeclaredMethod("enterField",DosSave::class.java).apply { isAccessible=true }.invoke(game,save) }
    private fun data()=field<OpeningTimeline>("opening").data
    private fun movement()=field<FieldMovement>("fieldMovement")
    private fun array(values:List<Int>)=JsonValue(JsonValue.ValueType.array).apply { values.forEach { addChild(JsonValue(it.toLong())) } }
    override fun create() {
        val audio=Gdx.audio
        Gdx.audio=object:Audio by audio {
            override fun newMusic(file:com.badlogic.gdx.files.FileHandle)=audio.newMusic(file).apply { volume=0f }.also { music+=it }
            override fun newSound(file:com.badlogic.gdx.files.FileHandle):com.badlogic.gdx.audio.Sound {
                val sound=audio.newSound(file)
                return object:com.badlogic.gdx.audio.Sound by sound { override fun play():Long { effects++;return sound.play(0f) } }
            }
        }
        val nativeInput=Gdx.input;val nativeGraphics=Gdx.graphics
        input=object:Input by nativeInput { override fun isKeyPressed(key:Int)=key in held;override fun isKeyJustPressed(key:Int)=if(key==Input.Keys.ANY_KEY) pressed.isNotEmpty() else key in pressed }
        graphics=object:Graphics by nativeGraphics { override fun getDeltaTime()=delta }
        Gdx.input=input;Gdx.graphics=graphics;game.create();game.resize(nativeGraphics.width,nativeGraphics.height);rules=field("fieldRules")
    }
    private data class Node(val x:Int,val y:Int,val layer:Int)
    private fun route(event:Int,goal:((Node,Int)->Boolean)?=null) {
        val map=session().map;val mover=movement();val npcs=field<FieldNpcs?>("fieldNpcs")
        val start=Node(mover.x.roundToInt(),mover.y.roundToInt(),mover.layer)
        val queue=ArrayDeque<Node>();queue+=start;val visited=mutableSetOf(start);val previous=mutableMapOf<Node,Pair<Node,Int>>()
        var path:List<Int>?=null
        val travel=field<BuildingTravel>("buildingTravel")
        search@while(queue.isNotEmpty()) {
            val node=queue.removeFirst()
            for(d in listOf(1,2,4,8)) {
                val trigger=rules.event(map,node.x.toFloat(),node.y.toFloat(),d)
                if(trigger==event && (goal==null || goal(node,d))) {
                    val result=mutableListOf(d);var cursor=node
                    while(cursor!=start) { val (parent,direction)=previous.getValue(cursor);result+=direction;cursor=parent }
                    path=result.reversed();break@search
                }
                if(trigger!=null && (trigger==0 || (map==4 && trigger==9) ||
                    travel.travel(session(),trigger)!=null || (map in setOf(4,12) && trigger==1))) continue
                if(npcs?.blocksPlayer(node.x.toFloat(),node.y.toFloat(),d)==true) continue
                val step=rules.step(map,node.x.toFloat(),node.y.toFloat(),d,node.layer)
                if(step.direction==0) continue
                val next=Node(node.x+FieldRules.dx(step.direction)*8,node.y+FieldRules.dy(step.direction)*8,step.layer)
                if(visited.add(next)) { previous[next]=node to d;queue+=next }
            }
        }
        check(path!=null) { "건물 접근 실패: 맵 $map, 이벤트 $event, $start" }
        for((index,d) in path.withIndex()) {
            val beforeMap=session().map;val beforeX=movement().x;val beforeY=movement().y
            held+=when(d) { 1->Input.Keys.W;2->Input.Keys.D;4->Input.Keys.S;else->Input.Keys.A }
            if(index==path.lastIndex) tick() else repeat(8) { tick() }
            held.clear();steps++
            if(index!=path.lastIndex && beforeMap==session().map && movement().x==beforeX && movement().y==beforeY) {
                idle(16);route(event,goal);return
            }
        }
        check(scene()=="FIELD");idle()
    }
    private fun capture(name:String) {
        val map=session().map;val source=data().get("maps").get(map.toString());val width=source.getInt("width")*8;val height=source.getInt("height")*8
        val mover=movement();val npcs=field<FieldNpcs>("fieldNpcs")
        val actorRows=npcs.actors.map { listOf(it.sprite,it.x,it.y,it.frame,it.mask) }+listOf(listOf(0,mover.x.toInt(),mover.y.toInt(),mover.pose,mover.layer))
        val camera=field<OrthographicCamera>("camera")
        fun aligned(current:Float,limit:Int,extent:Int,axis:Int):Int = (0..limit step 8).sortedBy { abs(it-current) }.first { scroll ->
            actorRows.all { a -> val p=a[axis];p+24<=scroll || p>=scroll+extent || (p>=scroll && p+24<=scroll+extent) } &&
                actorRows.last()[axis] in scroll..scroll+extent-24
        }
        val sx=aligned(camera.position.x-160,width-320,320,1);val sy=aligned(height-camera.position.y-100,height-200,200,2)
        camera.position.set(sx+160f,height-sy-100f,0f)
        delta=0f;tick();delta=1f/70f
        val folder="desktop/build/reports/buildings"
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),image,-1,true) } finally { image.dispose() }
        @Suppress("UNCHECKED_CAST") val cells=OpeningRenderer::class.java.getDeclaredField("cells").apply { isAccessible=true }.get(field<OpeningRenderer>("openingRenderer")) as Map<Int,IntArray>
        val tiles=(0 until 25).flatMap { y -> (0 until 40).map { x -> cells.getValue(map)[(sy/8+y)*(width/8)+sx/8+x] } }
        captures.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
            addChild("name",JsonValue(name));addChild("map",JsonValue(map.toLong()));addChild("scroll",array(listOf(sx,sy)))
            addChild("actors",JsonValue(JsonValue.ValueType.array).apply { actorRows.forEach { addChild(array(it)) } });addChild("tiles",array(tiles))
            field<FieldDialogue?>("fieldDialogue")?.let { talk -> addChild("page",com.badlogic.gdx.utils.JsonReader().parse(talk.page.toJson(JsonWriter.OutputType.json)));addChild("visible",JsonValue(talk.visibleCharacters.toLong())) }
        })
    }
    private fun saveAndReload() {
        val before=session();val mover=movement();val position=mover.x to mover.y
        press(Input.Keys.ESCAPE);press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        if(scene()=="CONFIRM_SAVE") { press(Input.Keys.DOWN);press(Input.Keys.ENTER) }
        check(scene()=="PAUSE" && Files.size(saves.resolve("SAVE0.DAT"))==2944L)
        press(Input.Keys.DOWN);press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        check(scene()=="FIELD" && session().map==before.map && session().variable(0)==before.variable(0))
        check(position==(movement().x to movement().y) && session().variable(8)==before.variable(8) && session().variable(99)==123)
        check(field<com.badlogic.gdx.audio.Music>("openingMusic").isPlaying)
    }
    override fun render() {
        if(completed) return
        val base=DosSave.afterOpening(Gdx.files.internal("assets/data/original/start.dat").readBytes())
        for(stage in listOf(0,3)) for(event in 2..8) {
            val map=if(stage<3) 4 else 12
            load(base.travel(map,0,0,88,24,8,0,mapOf(0 to 3,4 to 24,7 to 1,8 to stage,99 to 123)))
            val rectangle=rules.data.get("maps").get(map.toString()).get("rectangles").first { it[4].asInt()==event }
            if(event==3) {
                val attributes=rules.data.get("maps").get(map.toString()).get("attributes")
                for(y in rectangle[1].asInt()..rectangle[3].asInt()) for(x in rectangle[0].asInt()..rectangle[2].asInt()) check(attributes[y*120+x].asInt() and 12==0)
                continue
            }
            val door=rules.data.get("maps").get(map.toString()).get("doors").firstOrNull { it.getInt("x")/8+1 in rectangle[0].asInt()..rectangle[2].asInt() && it.getInt("y")/8-2==rectangle[1].asInt() }
            if(door!=null) {
                val x=door.getInt("x");val y=door.getInt("y")
                route(if(stage<3 && event==8) 9 else 0) { node,d -> node.x==x && node.y==y && d==1 }
                if(stage<3 && event==8) {
                    check(field<FieldDialogue?>("fieldDialogue")!=null && session().map==4)
                    idle();capture("locked-joel")
                    while(field<FieldDialogue?>("fieldDialogue")!=null) press(Input.Keys.ENTER)
                    check(session().variable(8)==0);continue
                }
                check(rules.event(map,x.toFloat(),y.toFloat(),1)==null)
                if(event==2) capture("opened-$map")
            }
            route(event)
            val expectedMap=when(event) { 2,3->5;4->8;5->10;6->11;7->6;else->7 }
            check(session().map==expectedMap && session().variable(8)==stage)
            if(stage==3 || event in setOf(2,3)) capture("stage-$stage-entry-$event-room-$expectedMap")
            val track=if(expectedMap in setOf(5,7)) 10 else 24
            check(session().variable(4)==track && field<Int>("activeTrack")==track)
            saveAndReload()
            if(event==4) {
                route(1);check(session().map==9);capture("mayor-side-room-$stage");saveAndReload()
                route(0);check(session().map==8)
            }
            val exit=when(expectedMap) { 5->1;7->4;else->0 }
            route(exit);check(session().map==map && session().variable(0)==3 && session().variable(8)==stage)
            // 맵을 다시 읽으면 닫힌 문을 복원한다.
            rules.data.get("maps").get(map.toString()).get("doors").forEach { d ->
                check(rules.event(map,d.getFloat("x"),d.getFloat("y"),1) in listOf(0,9))
            }
            println("건물 왕복 통과: 진행 $stage, 입구 $event, 실내 $expectedMap")
        }
        check(effects>0)
        Gdx.files.local("desktop/build/reports/buildings/scenes.json").writeString(captures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("건물 실행 검사 완료: $steps 걸음, 11회 왕복·잠긴 문·비활성 옆문·내부 방 연결·실내 저장, ${captures.size} 화면, 효과음 $effects 회")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
