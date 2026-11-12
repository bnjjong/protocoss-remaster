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

/** 실제 필드·대화·장소명의 인물 겹침을 기록한다. 원작 순서는 Python에서 독립적으로 결정한다. */
object ActorDepthCheck {
    @JvmStatic fun main(args:Array<String>) {
        val saves=Files.createTempDirectory("protocoss-depth-qa-")
        try {
            val app=ActorDepthApplication(saves)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("인물 앞뒤 표시 검사");setWindowedMode(640,400)
                setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}

private class ActorDepthApplication(saves:Path):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private lateinit var input:Input
    private lateinit var graphics:Graphics
    private val captures=JsonValue(JsonValue.ValueType.array)
    var completed=false;private set
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String):T =
        ProtocossGame::class.java.getDeclaredField(name).apply { isAccessible=true }.get(game) as T
    private fun set(target:Any,name:String,value:Any?) =
        target.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(target,value)
    private fun tick() { Gdx.input=input;Gdx.graphics=graphics;game.render() }
    private fun data()=field<OpeningTimeline>("opening").data
    private fun source(map:Int)=data().get("maps").get(map.toString())
    private fun array(values:List<Int>)=JsonValue(JsonValue.ValueType.array).apply {
        values.forEach { addChild(JsonValue(it.toLong())) }
    }
    private fun load(map:Int,x:Int,y:Int) {
        val base=DosSave.afterOpening(Gdx.files.internal("assets/data/original/start.dat").readBytes())
        val m=source(map);val sx=((x-144)/8).coerceIn(0,m.getInt("width")-40)
        val sy=((y-88)/8).coerceIn(0,m.getInt("height")-25)
        val save=base.travel(map,sx,sy,x-sx*8+16,y-sy*8+16,8,0,mapOf(0 to 3,7 to 1,8 to 0))
        ProtocossGame::class.java.getDeclaredMethod("enterField",DosSave::class.java).apply { isAccessible=true }.invoke(game,save)
        tick()
    }
    private fun place(x:Int,y:Int,layer:Int=0) {
        val map=field<DosSave>("session").map;val m=source(map);val height=m.getInt("height")*8
        field<FieldMovement>("fieldMovement").apply { place(x.toFloat(),y.toFloat());this.layer=layer }
        field<Player>("player").setPosition(x+12f,height-y-12f)
    }
    private fun camera(sx:Int,sy:Int) {
        val height=source(field<DosSave>("session").map).getInt("height")*8
        field<OrthographicCamera>("camera").position.set(sx+160f,height-sy-100f,0f)
    }
    private fun capture(name:String) {
        tick()
        val number=field<DosSave>("session").map;val m=source(number)
        val camera=field<OrthographicCamera>("camera");val height=m.getInt("height")*8
        val sx=(camera.position.x-160).toInt();val sy=(height-camera.position.y-100).toInt()
        val folder="desktop/build/reports/actor-depth"
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),image,-1,true) } finally { image.dispose() }
        val actors=JsonValue(JsonValue.ValueType.array)
        val npcs=field<FieldNpcs?>("fieldNpcs")?.actors ?: m.get("npcs").map { FieldNpcs.Actor(it) }
        npcs.forEach { actors.addChild(array(listOf(it.sprite,it.x,it.y,it.frame,it.mask))) }
        val mover=field<FieldMovement>("fieldMovement")
        val player=field<Player>("player")
        check(player.x-12f==mover.x && height-player.y-12f==mover.y) { "기록 좌표와 표시 좌표가 다릅니다: $name" }
        actors.addChild(array(listOf(0,mover.x.toInt(),mover.y.toInt(),mover.pose,mover.layer)))
        @Suppress("UNCHECKED_CAST") val cells=OpeningRenderer::class.java.getDeclaredField("cells").apply { isAccessible=true }
            .get(field<OpeningRenderer>("openingRenderer")) as Map<Int,IntArray>
        val tiles=(0 until 25).flatMap { row -> (0 until 40).map { col -> cells.getValue(number)[(sy/8+row)*m.getInt("width")+sx/8+col] } }
        captures.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
            addChild("name",JsonValue(name));addChild("map",JsonValue(number.toLong()))
            addChild("scroll",array(listOf(sx,sy)));addChild("actors",actors);addChild("tiles",array(tiles))
            field<FieldDialogue?>("fieldDialogue")?.let { talk ->
                addChild("page",com.badlogic.gdx.utils.JsonReader().parse(talk.page.toJson(JsonWriter.OutputType.json)))
                addChild("visible",JsonValue(talk.visibleCharacters.toLong()))
            }
            field<PlaceBanner?>("placeBanner")?.let { banner ->
                addChild("banner",JsonValue(JsonValue.ValueType.`object`).apply {
                    addChild("text",JsonValue(banner.name));addChild("x",JsonValue(banner.x.toLong()))
                })
            }
        })
    }
    override fun create() {
        val audio=Gdx.audio;Gdx.audio=object:Audio by audio {
            override fun newMusic(file:com.badlogic.gdx.files.FileHandle)=audio.newMusic(file).apply { volume=0f }
        }
        val nativeInput=Gdx.input;val nativeGraphics=Gdx.graphics
        input=object:Input by nativeInput {
            override fun isKeyPressed(key:Int)=false
            override fun isKeyJustPressed(key:Int)=false
        }
        graphics=object:Graphics by nativeGraphics { override fun getDeltaTime()=0f }
        Gdx.input=input;Gdx.graphics=graphics;game.create();game.resize(nativeGraphics.width,nativeGraphics.height)
    }
    private fun overlapResidents() {
        for(map in listOf(2,4,5,6,7,8,10,11,12)) {
            val raw=source(map).get("npcs")
            for(index in 0 until raw.size) {
                val npc=raw[index];val x=npc.getInt("x");val y=npc.getInt("y")
                load(map,x,y)
                for(dy in listOf(-8,0,8)) {
                    if(y+dy !in 0..source(map).getInt("height")*8-24) continue
                    place(x,y+dy);capture("map-$map-npc-$index-y-$dy")
                }
            }
        }
        // 빈 맵과 움직이지 않는 원작 인물도 같은 경로를 사용한다.
        load(1,160,128);capture("empty-cabin")
    }
    private fun cluster() {
        load(4,400,480);camera(256,392)
        val npcs=field<FieldNpcs>("fieldNpcs").actors
        npcs.forEach { set(it,"x",0);set(it,"y",0) }
        for(i in 0..2) {
            set(npcs[i],"x",400+i*4);set(npcs[i],"y",if(i==2)472 else 480)
            set(npcs[i],"mask",i)
        }
        place(400,480)
        capture("cluster-equal-y-layers")
        for(dy in -8..8) { place(400,480+dy);capture("cluster-crossing-$dy") }
        // 같은 인물 묶음을 대화창과 장소명 음영 영역 안으로 옮긴다.
        for((kind,screenY) in listOf("dialogue" to 138,"banner" to 8)) {
            val y=392+screenY
            for(i in 0..2) { set(npcs[i],"y",y+if(i==2)-8 else 0);set(npcs[i],"mask",0) }
            for(dy in listOf(-8,0,8)) {
                place(400,y+dy)
                if(kind=="dialogue") {
                    val pages=requireNotNull(FieldDialogue.pages(data(),14,0))
                    set(game,"fieldDialogue",FieldDialogue(-1,pages).apply { advance() })
                } else set(game,"placeBanner",PlaceBanner("엘가임").apply { update(1f) })
                capture("$kind-cluster-$dy")
                set(game,"fieldDialogue",null);set(game,"placeBanner",null)
            }
        }
    }
    override fun render() {
        if(completed)return
        overlapResidents();cluster()
        Gdx.files.local("desktop/build/reports/actor-depth/scenes.json")
            .writeString(captures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("인물 겹침 실행 기록: ${captures.size}장, 실내·마을·정적 인물·동률·높이 층·1픽셀 교차·대화·장소명")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
