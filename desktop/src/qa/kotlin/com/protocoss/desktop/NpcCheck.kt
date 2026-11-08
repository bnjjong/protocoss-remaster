package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.JsonValue
import com.protocoss.ProtocossGame
import com.protocoss.field.*
import com.protocoss.opening.OpeningRenderer
import com.protocoss.opening.OpeningTimeline
import com.protocoss.player.Player
import com.protocoss.save.DosSave
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor

/** 사용자 저장·음량을 바꾸지 않고 원본 폴더 없는 주민 대화와 저장 복귀를 실행한다. */
object NpcCheck {
    @JvmStatic fun main(args:Array<String>) {
        val saves=Files.createTempDirectory("protocoss-npc-qa-")
        try {
            val app=NpcApplication(saves)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("주민 이동·대화 자동 검사");setWindowedMode(640,400);setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
private class NpcApplication(private val saves:Path):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val held=mutableSetOf<Int>();private val pressed=mutableSetOf<Int>()
    private lateinit var input:Input;private lateinit var graphics:Graphics
    private var delta=1f/70f
    private val captures=JsonValue(JsonValue.ValueType.array)
    private var conversations=0
    var completed=false;private set
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String)=ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible=true }.get(game) as T
    private fun tick() { Gdx.input=input;Gdx.graphics=graphics;game.render();pressed.clear() }
    private fun press(key:Int) { pressed+=key;tick() }
    private fun idle(frames:Int=80) { repeat(frames) { tick() } }
    private fun session()=field<DosSave>("session")
    private fun scene()=field<Any>("scene").toString()
    private fun load(save:DosSave) { ProtocossGame::class.java.getDeclaredMethod("enterField",DosSave::class.java).apply { isAccessible=true }.invoke(game,save) }
    private fun position()=field<Player>("player").let { it.x to it.y }
    private fun source(map:Int)=field<OpeningTimeline>("opening").data.get("maps").get(map.toString())
    private fun array(values:List<Int>)=JsonValue(JsonValue.ValueType.array).apply { values.forEach { addChild(JsonValue(it.toLong())) } }
    private fun capture(name:String) {
        // 원작의 8픽셀 스크롤에 맞춘 같은 카메라·같은 인물 상태로 GPU 출력을 기록한다.
        val camera=field<OrthographicCamera>("camera")
        camera.position.x=floor((camera.position.x-160)/8)*8+160
        camera.position.y=600-(floor((600-camera.position.y-100)/8)*8+100)
        delta=0f;tick();delta=1f/70f
        val folder="desktop/build/reports/npcs"
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),image,-1,true) } finally { image.dispose() }
        val npcs=field<FieldNpcs>("fieldNpcs");val movement=field<FieldMovement>("fieldMovement")
        val talk=field<FieldDialogue?>("fieldDialogue")
        val sx=(camera.position.x-160).toInt();val sy=(600-camera.position.y-100).toInt()
        val actors=JsonValue(JsonValue.ValueType.array)
        npcs.actors.forEach { actors.addChild(array(listOf(it.sprite,it.x,it.y,it.frame,it.mask))) }
        actors.addChild(array(listOf(0,movement.x.toInt(),movement.y.toInt(),movement.pose,movement.layer)))
        @Suppress("UNCHECKED_CAST") val cells=OpeningRenderer::class.java.getDeclaredField("cells").apply { isAccessible=true }.get(field<OpeningRenderer>("openingRenderer")) as Map<Int,IntArray>
        val tiles=(0 until 25).flatMap { y -> (0 until 40).map { x -> cells.getValue(session().map)[(sy/8+y)*120+sx/8+x] } }
        captures.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
            addChild("name",JsonValue(name));addChild("map",JsonValue(session().map.toLong()))
            addChild("scroll",array(listOf(sx,sy)));addChild("actors",actors);addChild("tiles",array(tiles))
            if(talk!=null) { addChild("page",com.badlogic.gdx.utils.JsonReader().parse(talk.page.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)));addChild("visible",JsonValue(talk.visibleCharacters.toLong())) }
        })
    }
    override fun create() {
        val audio=Gdx.audio;Gdx.audio=object:Audio by audio { override fun newMusic(file:com.badlogic.gdx.files.FileHandle)=audio.newMusic(file).apply { volume=0f } }
        val nativeInput=Gdx.input;val nativeGraphics=Gdx.graphics
        input=object:Input by nativeInput { override fun isKeyPressed(key:Int)=key in held;override fun isKeyJustPressed(key:Int)=if(key==Input.Keys.ANY_KEY) pressed.isNotEmpty() else key in pressed }
        graphics=object:Graphics by nativeGraphics { override fun getDeltaTime()=delta }
        Gdx.input=input;Gdx.graphics=graphics;game.create();game.resize(nativeGraphics.width,nativeGraphics.height)
    }
    private fun talk(stage:Int,index:Int,base:DosSave) {
        val map=if(stage<3) 4 else 12
        val raw=source(map).get("npcs")[index]
        val x=raw.getInt("x");val y=raw.getInt("y")
        val fresh=FieldNpcs(source(map))
        val options=listOf(Triple(x-24,y,4),Triple(x,y+16,0),Triple(x,y-16,8),Triple(x+24,y,12))
        val spot=options.firstOrNull { fresh.talkTarget(it.first.toFloat(),it.second.toFloat(),it.third,0)==index }
            ?: error("접근 가능한 주민 정면이 없습니다: $map $index")
        val sx=((spot.first-144)/8).coerceIn(0,80);val sy=((spot.second-136)/8).coerceIn(0,50)
        load(base.travel(map,sx,sy,spot.first-sx*8+16,spot.second-sy*8+16,spot.third,0,mapOf(0 to 3,4 to 24,7 to 1,8 to stage,99 to 123)))
        val before=position();press(Input.Keys.E)
        check(field<FieldDialogue?>("fieldDialogue")!=null) { "대화 시작 실패: $map $index" }
        repeat(100) { if(!field<FieldNpcs>("fieldNpcs").talkReady) tick() }
        check(field<FieldNpcs>("fieldNpcs").talkReady)
        val npcs=field<FieldNpcs>("fieldNpcs");val target=npcs.actors[index]
        val stopped=target.x to target.y
        val otherPositions=npcs.actors.map { it.x to it.y }
        held+=Input.Keys.W;held+=Input.Keys.SHIFT_LEFT;idle(160);held.clear()
        check(position()==before && (target.x to target.y)==stopped)
        check(npcs.actors.indices.any { it!=index && (npcs.actors[it].x to npcs.actors[it].y)!=otherPositions[it] })
        press(Input.Keys.ESCAPE);check(scene()=="FIELD")
        val event=raw.getInt("event");var pages=0
        while(field<FieldDialogue?>("fieldDialogue")!=null) {
            val dialogue=field<FieldDialogue>("fieldDialogue");idle(80)
            check(dialogue.pageComplete)
            if(event in setOf(14,19)) capture("stage-$stage-event-$event-page-${dialogue.pageIndex}")
            press(if(pages%2==0) Input.Keys.SPACE else Input.Keys.ENTER);pages++
            check(pages<10)
        }
        check(target.mode==0 && target.direction==raw.getInt("direction") && target.frame==raw.getInt("frame"))
        check(session().variable(1)==event && session().variable(8)==stage && session().variable(99)==123)
        check(position()==before)
        conversations++
    }
    private fun saveAndReload() {
        val before=session();val position=position()
        val npcs=field<FieldNpcs>("fieldNpcs");val stopped=npcs.actors.map { it.x to it.y }
        press(Input.Keys.ESCAPE);check(scene()=="PAUSE");idle(100)
        check(stopped==npcs.actors.map { it.x to it.y })
        press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        check(scene()=="PAUSE")
        check(Files.size(saves.resolve("SAVE0.DAT"))==2944L)
        press(Input.Keys.DOWN);press(Input.Keys.DOWN);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        check(scene()=="FIELD" && position()==position)
        check(session().variable(8)==before.variable(8) && session().variable(99)==123 && session().variable(1)==before.variable(1))
        val reset=field<FieldNpcs>("fieldNpcs")
        source(before.map).get("npcs").forEachIndexed { index,actor ->
            check(reset.actors[index].x==actor.getInt("x") && reset.actors[index].y==actor.getInt("y"))
        }
        check(field<com.badlogic.gdx.audio.Music>("openingMusic").isPlaying)
        idle(70)
        check(reset.actors.any { actor -> actor.kind==2 && (actor.x!=source(before.map).get("npcs")[reset.actors.indexOf(actor)].getInt("x") || actor.y!=source(before.map).get("npcs")[reset.actors.indexOf(actor)].getInt("y")) })
    }
    private fun liveCollision(base:DosSave) {
        load(base.travel(4,0,0,88,24,4,0,mapOf(0 to 3,7 to 1)))
        val npcs=field<FieldNpcs>("fieldNpcs");val mover=field<FieldMovement>("fieldMovement")
        val player=field<Player>("player");val actor=npcs.actors[1]
        fun place(x:Float,y:Float) { mover.place(x,y);player.setPosition(x+12,600-y-12) }
        var checked=false
        repeat(32) { tick() }
        for(attempt in 0 until 10) {
            val target=actor.tileX to actor.tileY
            place((actor.tileX-3)*8f,actor.tileY*8f)
            val before=position();held+=Input.Keys.D;tick();held.clear()
            if(target==(actor.tileX to actor.tileY)) { check(position()==before);checked=true;break }
        }
        check(checked) { "움직이는 주민과의 충돌을 확인하지 못했습니다." }
        place(72f,8f)
        repeat(700) { if(actor.tileX==100) tick() }
        check(actor.tileX!=100 && actor.tileY==58)
        place(776f,464f);val before=player.x
        held+=Input.Keys.D;tick();held.clear()
        check(player.x==before+1) { "주민이 떠난 초기 자리가 여전히 막힙니다." }
    }
    override fun render() {
        if(completed) return
        val base=DosSave.afterOpening(Gdx.files.internal("assets/data/original/start.dat").readBytes())
        liveCollision(base)
        for(stage in listOf(0,3,4)) {
            val map=if(stage<3) 4 else 12
            for(index in 0 until source(map).get("npcs").size) talk(stage,index,base)
        }
        saveAndReload()
        Gdx.files.local("desktop/build/reports/npcs/scenes.json").writeString(captures.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json),false,"UTF-8")
        println("주민 실행 검사 통과: $conversations 대화, 진행 0·3·4, 접근·이동 잠금·다른 주민 이동·대화 복귀·메뉴 정지·저장 불러오기, ${captures.size} 화면 기록")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
