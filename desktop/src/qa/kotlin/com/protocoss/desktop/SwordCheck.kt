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

/** 새 게임부터 검 회수까지 실제 입력으로 진행한다. 원본 폴더와 사용자 저장은 쓰지 않는다. */
object SwordCheck {
    @JvmStatic fun main(args:Array<String>) {
        val saves=Files.createTempDirectory("protocoss-sword-qa-")
        try {
            val app=SwordApplication(saves)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("검 수리 스토리 자동 검사");setWindowedMode(640,400);setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
private class SwordApplication(private val saves:Path):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val held=mutableSetOf<Int>();private val pressed=mutableSetOf<Int>()
    private lateinit var input:Input;private lateinit var graphics:Graphics;private lateinit var rules:FieldRules
    private var delta=1f/70f;private var steps=0;private var effects=0;private var joins=0;private var letters=0
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
    private data class Node(val x:Int,val y:Int,val layer:Int,val open:Boolean=false)
    private fun route(event:Int,goal:((Node,Int)->Boolean)?=null) {
        val map=session().map;val mover=movement();val npcs=field<FieldNpcs?>("fieldNpcs")
        val start=Node(mover.x.roundToInt(),mover.y.roundToInt(),mover.layer,mover.doorOpen)
        val queue=ArrayDeque<Node>();queue+=start;val visited=mutableSetOf(start);val previous=mutableMapOf<Node,Pair<Node,Int>>()
        var path:List<Int>?=null
        val travel=field<BuildingTravel>("buildingTravel")
        search@while(queue.isNotEmpty()) {
            val node=queue.removeFirst()
            for(d in listOf(1,2,4,8)) {
                val trigger=rules.event(map,node.x.toFloat(),node.y.toFloat(),d,node.open)
                val opened=node.open || (map==0 && trigger==0)
                if(trigger==event && (goal==null || goal(node,d))) {
                    val result=mutableListOf(d);var cursor=node
                    while(cursor!=start) { val (parent,direction)=previous.getValue(cursor);result+=direction;cursor=parent }
                    path=result.reversed();break@search
                }
                if(trigger!=null && ((trigger==0 && map!=0) || (map==4 && trigger==9) ||
                    travel.travel(session(),trigger)!=null || TownTravel.travel(session(),trigger)!=null || CabinDoors.travel(session(),trigger)!=null)) continue
                if(npcs?.blocksPlayer(node.x.toFloat(),node.y.toFloat(),d)==true) continue
                val step=rules.step(map,node.x.toFloat(),node.y.toFloat(),d,node.layer,opened)
                if(step.direction==0) continue
                val next=Node(node.x+FieldRules.dx(step.direction)*8,node.y+FieldRules.dy(step.direction)*8,step.layer,opened)
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
        check(scene() in setOf("FIELD","OPENING","CLIP"));idle()
    }
    private fun capture(name:String) {
        val cinematic=scene()=="OPENING";val timeline=field<OpeningTimeline>("opening")
        val map=if(cinematic) timeline.map else session().map
        val source=data().get("maps").get(map.toString());val width=source.getInt("width")*8;val height=source.getInt("height")*8
        val mover=movement();val camera=field<OrthographicCamera>("camera")
        val rows=if(cinematic) {
            val sprites=timeline.data.get("programs")[timeline.program].get("sprites")
            (if(timeline.continuesField) field<FieldNpcs>("fieldNpcs").actors.map { listOf(it.sprite,it.x,it.y,it.frame,it.mask) } else emptyList())+
                timeline.actors.map { listOf(sprites[it.sprite].asInt(),it.x.toInt(),it.y.toInt(),it.frame,it.mask) }
        } else field<FieldNpcs>("fieldNpcs").actors.map { listOf(it.sprite,it.x,it.y,it.frame,it.mask) }+listOf(listOf(0,mover.x.toInt(),mover.y.toInt(),mover.pose,mover.layer))
        var sx=if(cinematic) timeline.scrollX.toInt() else ((camera.position.x-160)/8).roundToInt()*8
        var sy=if(cinematic) timeline.scrollY.toInt() else ((height-camera.position.y-100)/8).roundToInt()*8
        sx=sx.coerceIn(0,width-320);sy=sy.coerceIn(0,height-200)
        if(!cinematic) camera.position.set(sx+160f,height-sy-100f,0f)
        delta=0f;tick();delta=1f/70f
        val folder="desktop/build/reports/sword"
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),image,-1,true) } finally { image.dispose() }
        @Suppress("UNCHECKED_CAST") val cells=OpeningRenderer::class.java.getDeclaredField("cells").apply { isAccessible=true }.get(field<OpeningRenderer>("openingRenderer")) as Map<Int,IntArray>
        val tiles=(0 until 25).flatMap { y -> (0 until 40).map { x -> cells.getValue(map)[(sy/8+y)*(width/8)+sx/8+x] } }
        captures.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
            addChild("name",JsonValue(name));addChild("map",JsonValue(map.toLong()));addChild("scroll",array(listOf(sx,sy)))
            addChild("actors",JsonValue(JsonValue.ValueType.array).apply { rows.forEach { addChild(array(it)) } });addChild("tiles",array(tiles))
            val talk=field<FieldDialogue?>("fieldDialogue")
            val page=if(cinematic) timeline.page else talk?.page
            if(page!=null) {
                addChild("page",com.badlogic.gdx.utils.JsonReader().parse(page.toJson(JsonWriter.OutputType.json)))
                addChild("visible",JsonValue((if(cinematic) timeline.visibleCharacters else talk!!.visibleCharacters).toLong()))
            }
        })
    }
    private fun saveAndReload() {
        val before=session();val mover=movement();val position=mover.x to mover.y
        press(Input.Keys.ESCAPE);press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        if(scene()=="CONFIRM_SAVE") { press(Input.Keys.DOWN);press(Input.Keys.ENTER) }
        check(scene()=="PAUSE" && Files.size(saves.resolve("SAVE0.DAT"))==2944L)
        press(Input.Keys.DOWN);press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        check(scene()=="FIELD" && session().map==before.map && session().variable(0)==before.variable(0))
        check(position==(movement().x to movement().y) && session().variable(8)==before.variable(8))
        check(session().bytes()[500]==before.bytes()[500] && session().bytes()[162]==before.bytes()[162])
        check(field<com.badlogic.gdx.audio.Music>("openingMusic").isPlaying)
    }
    private fun finishScene() {
        val seen=mutableSetOf<Int>()
        val continued=field<OpeningTimeline>("opening").continuesField
        val previousNpcs=field<FieldNpcs?>("fieldNpcs")
        val previousRenderer=field<OpeningRenderer>("openingRenderer")
        repeat(15000) {
            if(scene()=="FIELD") {
                if(continued) {
                    check(field<FieldNpcs?>("fieldNpcs")===previousNpcs)
                    check(field<OpeningRenderer>("openingRenderer")===previousRenderer)
                    check(field<Float>("fieldFade")==0f)
                }
                idle();return
            }
            val timeline=field<OpeningTimeline>("opening")
            if(continued) check(timeline.fadeAlpha==0f)
            if(scene()=="OPENING" && timeline.page!=null) {
                idle(80)
                val id=timeline.dialogueId!!
                if(id in setOf(316,317,106) && seen.add(id)) capture("scene-$id")
                pressed+=Input.Keys.ENTER
            }
            tick()
        }
        error("스토리 연출 종료 실패")
    }
    private fun banner() {
        idle()
        if(field<PlaceBanner?>("placeBanner")!=null) { press(Input.Keys.ENTER);idle(40) }
    }
    private fun shopEntry() {
        route(0) { n,d -> n.x==304 && n.y==144 && d==1 };route(2)
        check(session().map==5);idle()
    }
    private fun approachShop() {
        val mover=movement();val npcs=field<FieldNpcs>("fieldNpcs");val map=session().map
        val start=Node(mover.x.roundToInt(),mover.y.roundToInt(),mover.layer)
        val queue=ArrayDeque<Node>();queue+=start;val visited=mutableSetOf(start);val prev=mutableMapOf<Node,Pair<Node,Int>>()
        var path:List<Int>?=null
        search@while(queue.isNotEmpty()) {
            val n=queue.removeFirst()
            for(d in listOf(1,2,4,8)) {
                val pose=when(d) { 1->0;2->4;4->8;else->12 }
                val block=npcs.blocksPlayer(n.x.toFloat(),n.y.toFloat(),d)
                val step=rules.step(map,n.x.toFloat(),n.y.toFloat(),d,n.layer)
                if(npcs.talkTarget(n.x.toFloat(),n.y.toFloat(),pose,n.layer)==0 && (block || step.direction==0)) {
                    val result=mutableListOf(d);var cursor=n
                    while(cursor!=start) { val (parent,direction)=prev.getValue(cursor);result+=direction;cursor=parent }
                    path=result.reversed();break@search
                }
                if(block || step.direction==0 || rules.event(map,n.x.toFloat(),n.y.toFloat(),d)!=null) continue
                val next=Node(n.x+FieldRules.dx(step.direction)*8,n.y+FieldRules.dy(step.direction)*8,step.layer)
                if(visited.add(next)) { prev[next]=n to d;queue+=next }
            }
        }
        check(path!=null)
        for((index,d) in path.withIndex()) {
            held+=when(d) { 1->Input.Keys.W;2->Input.Keys.D;4->Input.Keys.S;else->Input.Keys.A }
            repeat(if(index==path.lastIndex) 1 else 8) { tick() };held.clear();steps++
        }
    }
    private fun shopTalk(stage:Int,label:String) {
        approachShop();val before=movement().x to movement().y;val inventory=session().bytes()[162]
        press(Input.Keys.E);check(field<FieldDialogue?>("fieldDialogue")!=null)
        var pages=0
        while(field<FieldDialogue?>("fieldDialogue")!=null) {
            val dialogue=field<FieldDialogue>("fieldDialogue");idle(80)
            check(dialogue.pageComplete)
            held+=Input.Keys.D;tick();held.clear();press(Input.Keys.ESCAPE)
            check(scene()=="FIELD" && before==(movement().x to movement().y))
            check(session().variable(8)==stage)
            if(dialogue.isReward) {
                check(session().bytes()[162].toInt()==((inventory.toInt()+1) and 255).coerceAtMost(99));capture("reward")
            } else if(pages==0 || (stage==0 && pages==12)) capture("$label-page-$pages")
            press(Input.Keys.ENTER);pages++;check(pages<20)
        }
        check(session().variable(8)==when(stage) { 0->1;3->4;else->stage })
        check(session().bytes()[473].toInt()==-1)
    }
    override fun render() {
        if(completed) return
        press(Input.Keys.ENTER);press(Input.Keys.SPACE);finishScene()
        held+=Input.Keys.A;tick();held.clear();finishScene();check(session().variable(6)==1)
        route(1);check(session().map==0)
        route(2);check(session().map==3)
        route(2);finishScene();check(session().variable(7)==1)
        route(1);check(session().map==4);banner()
        shopEntry();shopTalk(0,"deposit");saveAndReload()
        shopTalk(1,"not-ready-1");route(1);check(session().map==4);saveAndReload()
        held+=Input.Keys.A;tick();held.clear();check(scene()=="OPENING");joins++;finishScene()
        check(session().variable(8)==2 && session().bytes()[500].toInt()==1);saveAndReload()
        shopEntry();shopTalk(2,"not-ready-2");check(session().bytes()[162].toInt()==0);route(1)
        route(1);check(session().map==0);banner()
        route(1);check(scene()=="OPENING");letters++;check(field<Int>("activeTrack")==27);finishScene()
        check(session().map==1 && session().variable(8)==3 && session().bytes()[500].toInt()==0)
        check(field<Int>("activeTrack")==25);saveAndReload()
        route(1);check(session().map==0);route(2);check(session().map==12);banner()
        shopEntry();saveAndReload();shopTalk(3,"retrieve");check(session().bytes()[162].toInt()==1)
        saveAndReload();shopTalk(4,"repeat");check(session().bytes()[162].toInt()==1)
        check(joins==1 && letters==1)
        Gdx.files.local("desktop/build/reports/sword/scenes.json").writeString(captures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("검 수리 스토리 검사 완료: $steps 걸음, 진행 0→1→2→3→4, 합류·쪽지 각 1회, 맡기기·회수·중복 방지·저장 재개, ${captures.size} 화면")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
