package com.protocoss.desktop

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.protocoss.ProtocossGame
import com.protocoss.field.*
import com.protocoss.opening.OpeningRenderer
import com.protocoss.opening.OpeningTimeline
import com.protocoss.save.DosSave
import com.protocoss.script.EventArchive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.math.abs

/** 모든 등록 문에 실제 입력을 넣고 원작 명령·저장·충돌·화면을 확인한다. */
object EventDoorsCheck {
    @JvmStatic fun main(args:Array<String>) {
        val saves=Files.createTempDirectory("protocoss-event-doors-qa-")
        try {
            val app=EventDoorsApplication(saves)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("문 이벤트 자동 검사");setWindowedMode(640,400);setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
private class EventDoorsApplication(saves:Path):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val held=mutableSetOf<Int>();private val pressed=mutableSetOf<Int>()
    private lateinit var input:Input;private lateinit var graphics:Graphics
    private var delta=1f/70f;private var effects=0
    private val shots=JsonValue(JsonValue.ValueType.array)
    var completed=false;private set
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String)=ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible=true }.get(game) as T
    private fun tick() { Gdx.input=input;Gdx.graphics=graphics;game.render();pressed.clear() }
    private fun idle(frames:Int=1) { repeat(frames) { tick() } }
    private fun session()=field<DosSave>("session")
    private fun movement()=field<FieldMovement>("fieldMovement")
    private fun rules()=field<FieldRules>("fieldRules")
    private fun load(save:DosSave) { ProtocossGame::class.java.getDeclaredMethod("enterField",DosSave::class.java).apply { isAccessible=true }.invoke(game,save) }
    private fun walk() { held+=Input.Keys.W;tick();held.clear() }
    private fun bytes(s:String)=Base64.getDecoder().decode(s)
    private fun array(values:List<Int>)=JsonValue(JsonValue.ValueType.array).apply { values.forEach { addChild(JsonValue(it.toLong())) } }
    override fun create() {
        val audio=Gdx.audio
        Gdx.audio=object:Audio by audio {
            override fun newMusic(file:com.badlogic.gdx.files.FileHandle)=audio.newMusic(file).apply { volume=0f }
            override fun newSound(file:com.badlogic.gdx.files.FileHandle):com.badlogic.gdx.audio.Sound {
                val sound=audio.newSound(file)
                return object:com.badlogic.gdx.audio.Sound by sound { override fun play():Long { effects++;return sound.play(0f) } }
            }
        }
        val nativeInput=Gdx.input;val nativeGraphics=Gdx.graphics
        input=object:Input by nativeInput {
            override fun isKeyPressed(key:Int)=key in held
            override fun isKeyJustPressed(key:Int)=if(key==Input.Keys.ANY_KEY) pressed.isNotEmpty() else key in pressed
        }
        graphics=object:Graphics by nativeGraphics { override fun getDeltaTime()=delta }
        Gdx.input=input;Gdx.graphics=graphics;game.create();game.resize(nativeGraphics.width,nativeGraphics.height)
    }
    private fun capture(name:String) {
        val map=session().map;val source=field<OpeningTimeline>("opening").data.get("maps").get(map.toString())
        val width=source.getInt("width")*8;val height=source.getInt("height")*8;val m=movement()
        val npcs=field<FieldNpcs?>("fieldNpcs")?.actors ?: source.get("npcs").map { FieldNpcs.Actor(it) }
        val actors=npcs.map { listOf(it.sprite,it.x,it.y,it.frame,it.mask) }+listOf(listOf(0,m.x.toInt(),m.y.toInt(),m.pose,m.layer))
        val camera=field<OrthographicCamera>("camera")
        fun aligned(current:Float,limit:Int,extent:Int,axis:Int)=(0..limit step 8).sortedBy { abs(it-current) }.first { scroll ->
            actors.all { a -> val p=a[axis];p+24<=scroll || p>=scroll+extent || (p>=scroll && p+24<=scroll+extent) } && actors.last()[axis] in scroll..scroll+extent-24
        }
        val sx=aligned(camera.position.x-160,width-320,320,1);val sy=aligned(height-camera.position.y-100,height-200,200,2)
        camera.position.set(sx+160f,height-sy-100f,0f);delta=0f;tick();delta=1f/70f
        val folder="desktop/build/reports/event-doors"
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),image,-1,true) } finally { image.dispose() }
        @Suppress("UNCHECKED_CAST") val cells=OpeningRenderer::class.java.getDeclaredField("cells").apply { isAccessible=true }.get(field<OpeningRenderer>("openingRenderer")) as Map<Int,IntArray>
        val tiles=(0 until 25).flatMap { y -> (0 until 40).map { x -> cells.getValue(map)[(sy/8+y)*(width/8)+sx/8+x] } }
        shots.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
            addChild("name",JsonValue(name));addChild("map",JsonValue(map.toLong()));addChild("scroll",array(listOf(sx,sy)))
            addChild("actors",JsonValue(JsonValue.ValueType.array).apply { actors.forEach { addChild(array(it)) } });addChild("tiles",array(tiles))
            field<FieldDialogue?>("fieldDialogue")?.let { d -> addChild("page",JsonReader().parse(d.page.toJson(JsonWriter.OutputType.json)));addChild("visible",JsonValue(d.visibleCharacters.toLong())) }
        })
    }
    override fun render() {
        if(completed) return
        val cases=JsonReader().parse(Gdx.files.local("docs/restoration/event-doors-native.json")).get("cases")
        var opened=0;var locked=0
        for(row in cases) {
            val source=DosSave.parse(bytes(row.getString("before")));load(source)
            val m=movement();val position=m.x to m.y;val count=effects
            val photograph=row.getInt("scroll")==1 && row.getInt("stage")==0
            val label="map-${source.map}-${row.getInt("x")}-${row.getInt("y")}"
            check(rules().step(source.map,m.x,m.y,1,m.layer).direction==0)
            if(photograph) capture("$label-closed")
            walk()
            check(position==(m.x to m.y)) { "문 명령을 끝내기 전에 보행했습니다." }
            check(bytes(row.getString("after")).contentEquals(session().bytes())) { "문 저장 불일치: $label/${row.getInt("stage")}" }
            if(row.get("changes").size>0) {
                opened++;check(field<FieldScript?>("fieldScript")==null)
                check(m.pose==row.getInt("pose") && m.direction==row.getInt("direction"))
                check(rules().event(source.map,m.x,m.y,1)==null && rules().step(source.map,m.x,m.y,1,m.layer).direction==1)
                check(effects==count+1);idle(8);check(effects==count+1)
                if(photograph) capture("$label-opened")
            } else {
                locked++;check(field<FieldDialogue?>("fieldDialogue")!=null && field<FieldScript?>("fieldScript")!=null)
                check(rules().event(source.map,m.x,m.y,1)==9 && effects==count)
                idle(80);if(photograph) capture("$label-locked")
                pressed+=Input.Keys.ENTER;tick();check(field<FieldDialogue?>("fieldDialogue")==null)
            }
            load(source);check(rules().event(source.map,m.x,m.y,1)==row.getInt("argument"))
            check(rules().step(source.map,m.x,m.y,1,m.layer).direction==0)
        }
        // 맵 번호가 같아도 원본 명령을 제거하면 문이 열리지 않아야 한다.
        val source=DosSave.parse(bytes(cases[0].getString("before")))
        val archives=field<MutableMap<Int,EventArchive>>("eventArchives");val original=archives.getValue(0)
        val raw=Gdx.files.internal("assets/data/events/area00.gm").readBytes();val entry=original.resolve(1,0)
        check(original.byte(entry)==0xa)
        val count=(raw[4].toInt() and 255) or ((raw[5].toInt() and 255) shl 8)
        raw[6+count*2+entry]=(0xf1 xor 127).toByte()
        try {
            archives[0]=EventArchive(raw);load(source);val countBefore=effects;walk()
            check(!movement().doorOpen && rules().event(0,movement().x,movement().y,1)==0 && effects==countBefore)
        } finally { archives[0]=original }
        walk();check(movement().doorOpen && rules().event(0,movement().x,movement().y,1)==null)
        Gdx.files.local("desktop/build/reports/event-doors/scenes.json").writeString(shots.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("문 이벤트 실행 검사: ${cases.size} 조합·열림 $opened·잠김 $locked·저장 전체·중복 방지·재진입·원본 명령 제어, ${shots.size} 화면")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
