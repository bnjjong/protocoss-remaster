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
import com.protocoss.menu.ShopMenu
import com.protocoss.menu.ShopRenderer
import com.protocoss.menu.EquipmentRenderer
import com.protocoss.menu.TentRest
import com.protocoss.menu.ItemTargetMenu
import com.protocoss.menu.EquipmentMenu
import java.util.Base64

/** 새 게임부터 검 회수까지 실제 입력으로 진행한다. 원본 폴더와 사용자 저장은 쓰지 않는다. */
object SwordCheck {
    @JvmStatic fun main(args:Array<String>)=run(false)
    fun run(equipment:Boolean,party:Boolean=false,automatic:Boolean=false,items:Boolean=false,tent:Boolean=false,shop:Boolean=false,indoor:Boolean=false) {
        val saves=Files.createTempDirectory("protocoss-sword-qa-")
        try {
            val app=SwordApplication(saves,equipment,party,automatic,items,tent,shop,indoor)
            Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
                setTitle("검 수리 스토리 자동 검사");setWindowedMode(640,400);setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            })
            check(app.completed)
        } finally { Files.walk(saves).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
object EquipmentCheck { @JvmStatic fun main(args:Array<String>)=SwordCheck.run(true) }
object PartyEquipmentCheck { @JvmStatic fun main(args:Array<String>)=SwordCheck.run(true,true) }
object AutomaticEquipmentCheck { @JvmStatic fun main(args:Array<String>)=SwordCheck.run(true,true,true) }
object FieldItemsCheck { @JvmStatic fun main(args:Array<String>)=SwordCheck.run(true,true,true,true) }
object TentCheck { @JvmStatic fun main(args:Array<String>)=SwordCheck.run(true,true,true,true,true) }
object ShopCheck { @JvmStatic fun main(args:Array<String>)=SwordCheck.run(true,true,true,true,true,true) }
object IndoorCheck { @JvmStatic fun main(args:Array<String>) {
    if("--only-indoor" in args) SwordCheck.run(false,indoor=true) else SwordCheck.run(true,true,true,true,true,true,true)
} }
private class SwordApplication(private val saves:Path,private val equipment:Boolean,private val partyEquipment:Boolean,private val automaticEquipment:Boolean,private val fieldItems:Boolean,private val tentEnabled:Boolean,private val shopEnabled:Boolean,private val indoorEnabled:Boolean):ApplicationAdapter() {
    private val game=ProtocossGame(null,saves)
    private val held=mutableSetOf<Int>();private val pressed=mutableSetOf<Int>()
    private lateinit var input:Input;private lateinit var graphics:Graphics;private lateinit var rules:FieldRules
    private var delta=1f/70f;private var steps=0;private var effects=0;private var joins=0;private var letters=0
    private val captures=JsonValue(JsonValue.ValueType.array)
    private val menuCaptures=JsonValue(JsonValue.ValueType.array)
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
            override fun newMusic(file:com.badlogic.gdx.files.FileHandle):com.badlogic.gdx.audio.Music {
                val source=audio.newMusic(file).apply { volume=0f }
                val result=if(!tentEnabled)source else object:com.badlogic.gdx.audio.Music by source {
                    private var requested=1f
                    override fun setVolume(value:Float) { requested=value;source.volume=0f }
                    override fun getVolume()=requested
                }
                music+=result;return result
            }
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
    private fun capture(name:String,directory:String="sword",record:JsonValue=captures) {
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
        val folder="desktop/build/reports/$directory"
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),image,-1,true) } finally { image.dispose() }
        @Suppress("UNCHECKED_CAST") val cells=OpeningRenderer::class.java.getDeclaredField("cells").apply { isAccessible=true }.get(field<OpeningRenderer>("openingRenderer")) as Map<Int,IntArray>
        val tiles=(0 until 25).flatMap { y -> (0 until 40).map { x -> cells.getValue(map)[(sy/8+y)*(width/8)+sx/8+x] } }
        record.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
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
    private fun approachShop(target:Int=0) {
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
                if(npcs.talkTarget(n.x.toFloat(),n.y.toFloat(),pose,n.layer)==target && (block || step.direction==0)) {
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
        if(stage>=4) { check(scene()=="SHOP");press(Input.Keys.ESCAPE);check(scene()=="FIELD") }
        check(session().variable(8)==when(stage) { 0->1;3->4;else->stage })
        check(session().bytes()[473].toInt()==-1)
    }
    private fun openEquipment() {
        check(scene()=="FIELD");press(Input.Keys.ESCAPE);repeat(5) { press(Input.Keys.DOWN) };press(Input.Keys.ENTER)
        check(scene()=="WEAPON")
    }
    private fun beginWeaponList() {
        press(Input.Keys.ENTER);press(Input.Keys.DOWN);press(Input.Keys.DOWN);press(Input.Keys.ENTER)
    }
    private fun captureMenu(name:String) {
        val folder="desktop/build/reports/equipment"
        val image=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),image,-1,true) } finally { image.dispose() }
        val weapon=field<EquipmentMenu>("equipmentMenu")
        menuCaptures.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
            addChild("name",JsonValue(name));addChild("scene",JsonValue(scene()))
            addChild("save",JsonValue(Base64.getEncoder().encodeToString(session().bytes())))
            addChild("character",JsonValue(weapon.character.toLong()));addChild("slot",JsonValue(weapon.slot.toLong()));
            addChild("tab",JsonValue(weapon.tab.toLong()));addChild("stage",JsonValue(weapon.stage.toString()));addChild("selected",JsonValue(weapon.selected.toLong()));addChild("scroll",JsonValue(weapon.scroll.toLong()))
            val target=field<ItemTargetMenu?>("itemTarget")
            addChild("item_target",JsonValue((target?.item ?: -1).toLong()));addChild("item_target_index",JsonValue((target?.index ?: 0).toLong()))
            addChild("inventory_equipment",JsonValue(field<Boolean>("inventoryEquipment")))
            addChild("inventory_selected",JsonValue(field<Int>("inventorySelection").toLong()));addChild("inventory_scroll",JsonValue(field<Int>("inventoryScroll").toLong()))
        })
    }
    private fun closeEquipment() { repeat(4) { if(scene()=="WEAPON" || scene()=="PAUSE") press(Input.Keys.ESCAPE) };check(scene()=="FIELD") }
    private fun checkEquipment() {
        val model=field<EquipmentRenderer>("equipmentRenderer").model
        val before=session().bytes();val position=movement().x to movement().y
        openEquipment();captureMenu("weapon-home")
        val clock=field<FieldNpcs?>("fieldNpcs")!!.actors.map { it.x to it.y }
        held+=Input.Keys.W;idle(80);held.clear();press(Input.Keys.SPACE)
        check(position==(movement().x to movement().y));check(clock==field<FieldNpcs>("fieldNpcs").actors.map { it.x to it.y })
        press(Input.Keys.DOWN);press(Input.Keys.DOWN);captureMenu("weapon-slot");press(Input.Keys.ENTER);captureMenu("dagger-preview")
        press(Input.Keys.ENTER);check(model.equipped(session())==0 && model.quantity(session(),0,true)==0)
        check(model.stats(session())[0]==20);captureMenu("dagger-equipped")
        closeEquipment();saveAndReload();check(model.equipped(session())==0 && model.stats(session())[0]==20)
        openEquipment();beginWeaponList()
        check(model.equipped(session())==255 && model.quantity(session(),0,true)==1)
        press(Input.Keys.ESCAPE);captureMenu("cancel-detached");closeEquipment();saveAndReload()
        check(model.equipped(session())==255 && model.quantity(session(),0,true)==1)
        press(Input.Keys.ESCAPE);repeat(4) { press(Input.Keys.DOWN) };press(Input.Keys.ENTER)
        check(scene()=="INVENTORY");captureMenu("empty-items")
        press(Input.Keys.RIGHT);captureMenu("dagger-inventory");press(Input.Keys.ENTER)
        check(model.quantity(session(),0,true)==1);press(Input.Keys.ESCAPE);press(Input.Keys.ESCAPE)
        load(DosSave.parse(session().bytes().also { it.fill(0,162,337) }))
        openEquipment();beginWeaponList();captureMenu("empty-weapons")
        val empty=session().bytes();press(Input.Keys.ENTER);check(empty.contentEquals(session().bytes()));closeEquipment()
        val fixture=session().bytes().also { b ->
            b.fill(1,162,337);b.fill(2,123,162)
            b[475]=(-1).toByte();b[476]=90
        }
        load(DosSave.parse(fixture));openEquipment();beginWeaponList()
        repeat(8) { press(Input.Keys.DOWN) };captureMenu("weapon-scroll")
        val choices=model.candidates(session());val two=choices.indexOfFirst { it.category==5 }
        while(field<EquipmentMenu>("equipmentMenu").selected!=two) press(if(field<EquipmentMenu>("equipmentMenu").selected<two) Input.Keys.DOWN else Input.Keys.UP)
        val blocked=session().bytes();press(Input.Keys.ENTER);check(blocked.contentEquals(session().bytes()));captureMenu("shield-blocked")
        closeEquipment();press(Input.Keys.ESCAPE);repeat(4) { press(Input.Keys.DOWN) };press(Input.Keys.ENTER)
        repeat(9) { press(Input.Keys.DOWN) };captureMenu("items-scroll")
        repeat(50) { press(Input.Keys.DOWN) };captureMenu("items-special")
        press(Input.Keys.RIGHT);repeat(7) { press(Input.Keys.DOWN) };captureMenu("equipment-inventory-scroll")
        press(Input.Keys.ESCAPE);press(Input.Keys.ESCAPE)
        load(DosSave.parse(before));check(model.quantity(session(),0,true)==1)
        Gdx.files.local("desktop/build/reports/equipment/scenes.json").writeString(menuCaptures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("무기 메뉴 검사 완료: 검 회수→단검 장착→저장 재개·취소 시 해제·스크롤·방패 제한·필드 정지, ${menuCaptures.size} 화면")
    }
    private fun gear()=field<EquipmentMenu>("equipmentMenu")
    private fun openGear(character:Int,slot:Int) {
        openEquipment()
        repeat(11) { if(gear().character!=character) press(Input.Keys.DOWN) }
        check(gear().character==character)
        press(Input.Keys.ENTER)
        val queue=ArrayDeque<Int>();queue+=gear().slot
        val previous=mutableMapOf<Int,Pair<Int,String>>()
        val visited=mutableSetOf(gear().slot)
        val model=field<EquipmentRenderer>("equipmentRenderer").model
        while(queue.isNotEmpty() && slot !in visited) {
            val current=queue.removeFirst()
            for(d in listOf("up","down","left","right")) {
                val next=model.data.get("slot_navigation").get(d)[current].asInt()
                if(visited.add(next)) { previous[next]=current to d;queue+=next }
            }
        }
        val path=mutableListOf<String>();var cursor=slot
        while(cursor!=gear().slot) { val (parent,d)=previous.getValue(cursor);path+=d;cursor=parent }
        path.reversed().forEach { press(when(it) { "up"->Input.Keys.UP;"down"->Input.Keys.DOWN;"left"->Input.Keys.LEFT;else->Input.Keys.RIGHT }) }
        check(gear().slot==slot);press(Input.Keys.ENTER)
    }
    private fun chooseGear(id:Int) {
        val model=field<EquipmentRenderer>("equipmentRenderer").model
        val index=model.candidates(session(),gear().character,gear().slot).indexOfFirst { it.id==id }
        check(index>=0)
        while(gear().selected!=index) press(if(gear().selected<index) Input.Keys.DOWN else Input.Keys.UP)
    }
    private fun checkPartyEquipment() {
        val model=field<EquipmentRenderer>("equipmentRenderer").model
        val base=session().bytes();val report=com.badlogic.gdx.utils.JsonReader().parse(Files.readString(Path.of("docs/party-equipment-native.json")))
        for(character in 0..10) {
            val slot=character%6
            val bytes=Base64.getDecoder().decode(report.get("fixtures")[character*6+slot].asString())
            base.copyInto(bytes,92,92,119)
            base.copyInto(bytes,2350,2350,2352)
            for(i in 0..10) bytes[347+153*i]=if(i==character)1 else 0
            load(DosSave.parse(bytes));openGear(character,slot)
            captureMenu("party-$character-slot-$slot")
            val choices=model.candidates(session(),character,slot)
            if(choices.isNotEmpty()) {
                val expected=model.equip(session(),choices[0].id,character,slot)
                press(Input.Keys.ENTER);check(expected.bytes().contentEquals(session().bytes()))
                check(model.equipped(session(),character,slot)==choices[0].id)
            } else press(Input.Keys.ENTER)
            closeEquipment();val before=session().bytes();saveAndReload()
            for(i in 337 until 2020) check(session().bytes()[i]==before[i])
        }
        val bytes=base.copyOf().also { b -> b.fill(2,162,337);b.fill((-1).toByte(),473,479);b[387]=0 }
        load(DosSave.parse(bytes));openGear(0,5);chooseGear(174);press(Input.Keys.ENTER)
        check(session().bytes()[387].toInt()==12);captureMenu("gem-right-equipped");closeEquipment();saveAndReload()
        openGear(0,4);chooseGear(163);val blocked=session().bytes();press(Input.Keys.ENTER)
        check(blocked.contentEquals(session().bytes()));captureMenu("gem-left-blocked");closeEquipment()
        openGear(0,5);check(session().bytes()[387].toInt()==0);press(Input.Keys.ESCAPE);captureMenu("gem-removed");closeEquipment();saveAndReload()
        openGear(0,4);chooseGear(163);press(Input.Keys.ENTER);closeEquipment()
        openGear(0,5);chooseGear(174);press(Input.Keys.ENTER)
        check(model.equipped(session(),0,4)==163 && model.equipped(session(),0,5)==174 && session().bytes()[387].toInt()==12)
        captureMenu("two-gems-native");closeEquipment();saveAndReload()
        val cloak=base.copyOf().also { b -> b.fill(2,162,337);b.fill((-1).toByte(),473,479);b[387]=0 }
        // 오두막의 연속된 빈 바닥에서 70Hz 한 번의 이동 거리를 확인한다.
        var point:Pair<Int,Int>?=null
        search@for(y in 32..160 step 8) for(x in 24..264 step 8)
            if((0..2).all { rules.step(1,(x+it*8).toFloat(),y.toFloat(),2,0,true).direction==2 }) { point=x to y;break@search }
        val (x,y)=checkNotNull(point)
        load(DosSave.parse(cloak).travel(1,0,0,x+16,y+16,4,0,mapOf(297 to 1)))
        openGear(0,4);chooseGear(157);press(Input.Keys.ENTER);captureMenu("cloak-equipped");closeEquipment()
        check(field<Int>("fieldStep")==2 && movement().baseStep==2)
        val oldX=movement().x;held+=Input.Keys.D;tick();held.clear();check(movement().x==oldX+2);idle(4)
        saveAndReload();check(session().variable(297)==2 && movement().baseStep==2)
        openGear(0,4);check(field<Int>("fieldStep")==1);press(Input.Keys.ESCAPE);captureMenu("cloak-removed");closeEquipment();saveAndReload()
        check(session().variable(297)==1 && movement().baseStep==1)
        val normalX=movement().x;held+=Input.Keys.D;tick();held.clear();check(movement().x==normalX+1)
        load(DosSave.parse(base))
        Gdx.files.local("desktop/build/reports/equipment/scenes.json").writeString(menuCaptures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("파티 장비 검사 완료: 11명·여섯 슬롯·장착/해제/거부·구슬 비대칭·망토 이동·저장 재개, ${menuCaptures.size} 화면")
    }
    private fun checkAutomaticEquipment() {
        val base=session().bytes()
        val report=com.badlogic.gdx.utils.JsonReader().parse(Files.readString(Path.of("docs/automatic-equipment-native.json")))
        fun normalize(bytes:ByteArray):ByteArray {
            base.copyInto(bytes,92,92,119);base.copyInto(bytes,2350,2350,2352)
            return bytes
        }
        fun patch(bytes:ByteArray,changes:JsonValue) { changes.forEach { bytes[it[0].asInt()]=it[1].asInt().toByte() } }
        for(row in report.get("cases").filter { it.getString("name").endsWith("-full") || it.getString("name") in listOf("0-empty","0-overflow","0-twohand","0-shield-only") }) {
            val name=row.getString("name");val character=row.getInt("character")
            val expected=normalize(Base64.getDecoder().decode(row.getString("before")))
            load(DosSave.parse(expected));openEquipment()
            repeat(11) { if(gear().character!=character) press(Input.Keys.DOWN) }
            check(gear().character==character)
            for((index,step) in row.get("steps").withIndex()) {
                val tab=if(step.getString("action")=="equip")1 else 2
                while(gear().tab!=tab) press(if(gear().tab<tab)Input.Keys.RIGHT else Input.Keys.LEFT)
                val position=movement().x to movement().y
                val actors=field<FieldNpcs>("fieldNpcs").actors.map { it.x to it.y }
                press(if(index%2==0)Input.Keys.ENTER else Input.Keys.SPACE)
                patch(expected,step.get("after"))
                check(expected.contentEquals(session().bytes())) { "자동 장비 저장 불일치: $name/$index ${expected.indices.filter { expected[it]!=session().bytes()[it] }.take(20).map { Triple(it,expected[it],session().bytes()[it]) }}" }
                check(gear().stage==EquipmentMenu.Stage.HOME && gear().tab==tab)
                check(field<Int>("fieldStep")==step.getInt("step") && movement().baseStep==step.getInt("step"))
                check(position==(movement().x to movement().y))
                check(actors==field<FieldNpcs>("fieldNpcs").actors.map { it.x to it.y })
                if(index==0 || index==2) {
                    captureMenu("auto-$name-${step.getString("action")}")
                    closeEquipment();val bytes=session().bytes();saveAndReload()
                    for(i in 123 until 2020) check(session().bytes()[i]==bytes[i])
                    check(session().variable(297)==step.getInt("step"))
                    // 저장 시각 검증 값만 새 파일의 값으로 이어받는다. 장비 변경은 전체 바이트로 비교한다.
                    session().bytes().copyInto(expected,2942,2942,2944)
                    openEquipment();repeat(11) { if(gear().character!=character) press(Input.Keys.DOWN) }
                }
            }
            closeEquipment()
        }
        for(row in report.get("controllers")) {
            val expected=normalize(Base64.getDecoder().decode(row.getString("before")))
            load(DosSave.parse(expected));openEquipment()
            for((index,key) in row.get("keys").withIndex()) {
                press(when(key.asInt()) { 72->Input.Keys.UP;80->Input.Keys.DOWN;75->Input.Keys.LEFT;77->Input.Keys.RIGHT;28->Input.Keys.ENTER;57->Input.Keys.SPACE;else->Input.Keys.ESCAPE })
                if(key.asInt()!=1) {
                    check(gear().stage==EquipmentMenu.Stage.HOME)
                    check(gear().tab==row.get("tabs")[index].asInt())
                    if(row.getString("name")=="tab-bounds") captureMenu("auto-tab-$index")
                }
            }
            patch(expected,row.get("after"));check(expected.contentEquals(session().bytes()))
            check(scene()=="PAUSE");press(Input.Keys.ESCAPE)
        }
        load(DosSave.parse(base))
        Gdx.files.local("desktop/build/reports/equipment/scenes.json").writeString(menuCaptures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("자동 장비 검사 완료: 11명·자동 장착/해제·반복·빈 소지품·수량 넘침·양손 무기·방패·탭/파티 순환·저장 재개, ${menuCaptures.size} 화면")
    }
    private fun checkFieldItems() {
        val base=session().bytes();val model=field<EquipmentRenderer>("equipmentRenderer").model
        val report=com.badlogic.gdx.utils.JsonReader().parse(Files.readString(Path.of("docs/field-items-native.json")))
        fun fixture(index:Int):ByteArray {
            val bytes=Base64.getDecoder().decode(report.get("fixtures")[index].asString())
            base.copyInto(bytes,92,92,119);base.copyInto(bytes,2350,2350,2352)
            return bytes
        }
        fun open(item:Int) {
            check(scene()=="FIELD");press(Input.Keys.ESCAPE);repeat(4) { press(Input.Keys.DOWN) };press(Input.Keys.ENTER)
            val rows=model.inventory(session(),false);val index=rows.indexOfFirst { it.id==item };check(index>=0)
            repeat(index) { press(Input.Keys.DOWN) }
        }
        fun close() {
            if(field<ItemTargetMenu?>("itemTarget")!=null) press(Input.Keys.ESCAPE)
            if(scene()=="INVENTORY") press(Input.Keys.ESCAPE)
            check(scene()=="PAUSE");press(Input.Keys.ESCAPE);check(scene()=="FIELD")
        }
        for((n,item) in report.get("ids").map { it.asInt() }.withIndex()) {
            val character=n%11;val variant=when(item) { 3->9;9->3;10->4;11,12->5;16->7;else->0 }
            val index=character*10+variant;val expected=fixture(index)
            val row=report.get("cases").first { it.getInt("fixture")==index && it.getInt("item")==item }
            load(DosSave.parse(expected));open(item);press(Input.Keys.SPACE)
            check(field<ItemTargetMenu?>("itemTarget")!=null)
            val partyIndex=model.party(session()).indexOf(character)
            repeat(partyIndex) { press(Input.Keys.RIGHT) }
            captureMenu("item-$item-target")
            val before=session().bytes();press(Input.Keys.SPACE);check(before.contentEquals(session().bytes()))
            val position=movement().x to movement().y
            val actors=field<FieldNpcs>("fieldNpcs").actors.map { it.x to it.y }
            held+=Input.Keys.W;idle(10);held.clear()
            check(position==(movement().x to movement().y));check(actors==field<FieldNpcs>("fieldNpcs").actors.map { it.x to it.y })
            press(Input.Keys.ENTER);press(Input.Keys.ENTER)
            row.get("after").forEach { expected[it[0].asInt()]=it[1].asInt().toByte() }
            check(expected.contentEquals(session().bytes())) { "아이템 상태 불일치: $character/$item" }
            captureMenu("item-$item-used")
            press(Input.Keys.ESCAPE)
            if(item in listOf(0,16,33)) captureMenu("item-$item-list")
            close();val saved=session().bytes();saveAndReload()
            for(i in 123 until 2020) check(saved[i]==session().bytes()[i])
            check(session().variable(297)==2 && movement().baseStep==2)
        }
        for((name,index,item) in listOf(Triple("full",2,0),Triple("dead",7,0),Triple("last",1,33))) {
            val expected=fixture(index)
            if(name=="last") { expected.fill(0,123,162);expected[123+item]=1 }
            load(DosSave.parse(expected));open(item);press(Input.Keys.ENTER)
            val cancel=session().bytes();press(Input.Keys.ESCAPE);check(cancel.contentEquals(session().bytes()))
            press(Input.Keys.ENTER);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
            val row=report.get("cases").first { it.getInt("fixture")==index && it.getInt("item")==item }
            row.get("after").forEach { expected[it[0].asInt()]=it[1].asInt().toByte() }
            check(expected.contentEquals(session().bytes()));captureMenu("item-$name")
            press(Input.Keys.ESCAPE)
            if(name=="last") check(scene()=="PAUSE")
            close();saveAndReload()
        }
        val raw=fixture(0)
        for(c in 0..10) raw[347+c*153]=if(c in listOf(0,2,5,7,10))1 else 0
        for((i,c) in listOf(0,2,5,7,10).withIndex()) raw[472+c*153]=(i+1).toByte()
        load(DosSave.parse(raw));open(0);press(Input.Keys.ENTER)
        for(key in listOf(Input.Keys.RIGHT,Input.Keys.RIGHT,Input.Keys.DOWN,Input.Keys.LEFT,Input.Keys.UP)) {
            press(key);captureMenu("item-grid-${field<ItemTargetMenu>("itemTarget").index}")
        }
        close()
        for(item in listOf(17,21,25,26,27,28,34,37,38)) {
            load(DosSave.parse(fixture(0)));open(item);val bytes=session().bytes();press(Input.Keys.ENTER)
            check(field<ItemTargetMenu?>("itemTarget")==null && bytes.contentEquals(session().bytes()));close()
        }
        load(DosSave.parse(base))
        Gdx.files.local("desktop/build/reports/equipment/scenes.json").writeString(menuCaptures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("대상 아이템 실행 검사: 19종·11명·치료/부활/회복 불필요·마지막 한 개·취소·대상 선택·저장 재개, ${menuCaptures.size} 화면")
    }
    private fun checkTent() {
        val base=session().bytes()
        val report=com.badlogic.gdx.utils.JsonReader().parse(Files.readString(Path.of("docs/tent-native.json")))
        val row=report.get("cases").first { it.getInt("fixture")==44 && it.getInt("permission")==1 && it.getInt("count")==1 }
        fun fixture(permission:Int=1,count:Int=1,track:Int=base.let { (it[2350].toInt() and 255) or ((it[2351].toInt() and 255) shl 8) }):ByteArray {
            val b=Base64.getDecoder().decode(report.get("fixtures")[44].asString())
            base.copyInto(b,92,92,119);b[160]=count.toByte()
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { putShort(2346,permission.toShort());putShort(2350,track.toShort()) }
            return b
        }
        fun patch(b:ByteArray,key:String) { row.get(key).forEach { b[it[0].asInt()]=it[1].asInt().toByte() } }
        fun state()=field<TentRest?>("tentRest")
        fun open() { press(Input.Keys.ESCAPE);repeat(4) { press(Input.Keys.DOWN) };press(Input.Keys.ENTER);check(scene()=="INVENTORY") }
        val folder="desktop/build/reports/tent"
        val shots=JsonValue(JsonValue.ValueType.array)
        fun png(name:String) {
            val pixmap=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
            try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),pixmap,-1,true) } finally { pixmap.dispose() }
        }
        fun capture() {
            val rest=state() ?: return;val name="step-${shots.size}";png(name)
            shots.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
                addChild("name",JsonValue(name));addChild("phase",JsonValue(rest.phase.toString()));addChild("level",JsonValue(rest.level.toLong()))
                addChild("source",JsonValue(when(rest.phase) { TentRest.Phase.MENU_OUT->"menu";TentRest.Phase.RETURN_IN->"field";else->"sleep" }))
            })
        }
        for((permission,count) in listOf(0 to 1,2 to 1,65535 to 1,1 to 0)) {
            val raw=fixture(permission,count);load(DosSave.parse(raw));tick();open();press(Input.Keys.ENTER)
            check(scene()=="INVENTORY" && state()==null && raw.contentEquals(session().bytes()))
            press(Input.Keys.ESCAPE);press(Input.Keys.ESCAPE)
        }
        val raw=fixture();load(DosSave.parse(raw));tick();png("field")
        open();val cancel=session().bytes();press(Input.Keys.ESCAPE);press(Input.Keys.ESCAPE);check(cancel.contentEquals(session().bytes()))
        open();captureMenu("tent-menu");png("menu")
        val oldMusic=field<com.badlogic.gdx.audio.Music>("openingMusic")
        val position=movement().x to movement().y;val actors=field<FieldNpcs>("fieldNpcs").actors.map { it.x to it.y }
        val oldSave=Files.readAllBytes(saves.resolve("SAVE0.DAT"))
        press(Input.Keys.ENTER);check(scene()=="REST");capture()
        repeat(140) {
            if(state()?.phase!=TentRest.Phase.WAIT) {
                press(Input.Keys.ESCAPE);check(scene()=="REST");capture()
                check(session().bytes()[160].toInt()==1)
            }
        }
        check(state()?.phase==TentRest.Phase.WAIT)
        val atWait=raw.copyOf();patch(atWait,"at_wait");check(atWait.contentEquals(session().bytes()))
        val sleep=field<com.badlogic.gdx.audio.Music>("tentMusic")
        check(!oldMusic.isPlaying && sleep.isPlaying && sleep.isLooping && sleep.volume==1f)
        game.pause();val level=state()!!.level;press(Input.Keys.SPACE);check(state()!!.phase==TentRest.Phase.WAIT && state()!!.level==level && !sleep.isPlaying)
        game.resume();check(sleep.isPlaying)
        delta=1f;held+=Input.Keys.A;repeat(120) { tick() };held.clear();delta=1f/70f
        check(state()?.phase==TentRest.Phase.WAIT && atWait.contentEquals(session().bytes()))
        check(position==(movement().x to movement().y));check(actors==field<FieldNpcs>("fieldNpcs").actors.map { it.x to it.y })
        check(oldSave.contentEquals(Files.readAllBytes(saves.resolve("SAVE0.DAT"))))
        press(Input.Keys.ESCAPE);capture()
        repeat(140) { if(scene()=="REST") { tick();capture() } }
        check(scene()=="FIELD" && state()==null && !sleep.isPlaying)
        val expected=raw.copyOf();patch(expected,"after");check(expected.contentEquals(session().bytes()))
        check(field<com.badlogic.gdx.audio.Music>("openingMusic").isPlaying && field<com.badlogic.gdx.audio.Music>("openingMusic").volume==1f)
        check(field<Int>("activeTrack")==session().variable(4) && field<Int>("fieldStep")==2)
        png("returned");val result=session().bytes();saveAndReload()
        for(i in 123 until 2942) check(result[i]==session().bytes()[i])
        for(track in listOf(24,25)) {
            val initial=fixture(count=2,track=track);load(DosSave.parse(initial));tick()
            repeat(2) {
                open();press(Input.Keys.SPACE)
                repeat(140) { if(state()?.phase!=TentRest.Phase.WAIT) tick() }
                check(state()?.phase==TentRest.Phase.WAIT);press(Input.Keys.A)
                repeat(140) { if(scene()=="REST") tick() }
                check(scene()=="FIELD" && session().bytes()[160].toInt()==1-it && field<Int>("activeTrack")==track)
            }
            saveAndReload();check(session().bytes()[160].toInt()==0)
        }
        load(DosSave.parse(base))
        Gdx.files.local("$folder/scenes.json").writeString(shots.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        Gdx.files.local("desktop/build/reports/equipment/scenes.json").writeString(menuCaptures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("텐트 실행 검사: 조건 거부·취소·회복·새 키 대기·120초 유지·일시정지·필드 정지·완료 후 수량·음악 10/24/25 복귀·반복·저장 재개, ${shots.size} 화면")
    }
    private fun checkShop() {
        val folder="desktop/build/reports/shop"
        val shots=JsonValue(JsonValue.ValueType.array)
        val model=field<ShopRenderer>("shopRenderer").model
        val base=session().bytes();val position=movement().x to movement().y
        val report=com.badlogic.gdx.utils.JsonReader().parse(Gdx.files.local("docs/shop-native.json"))
        fun state()=field<ShopMenu>("shopMenu")
        fun png(name:String) {
            val p=Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
            try { PixmapIO.writePNG(Gdx.files.local("$folder/$name.png"),p,-1,true) } finally { p.dispose() }
        }
        fun capture(name:String) {
            png(name)
            val renderer=field<ShopRenderer>("shopRenderer")
            val p=ShopRenderer::class.java.getDeclaredField("field").apply { isAccessible=true }.get(renderer) as Pixmap
            PixmapIO.writePNG(Gdx.files.local("$folder/$name-field.png"),p,-1,false)
            shots.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
                addChild("name",JsonValue(name));addChild("stage",JsonValue(state().stage.toString()))
                addChild("choice",JsonValue(state().choice.toLong()));addChild("selected",JsonValue(state().selected.toLong()))
                addChild("blink",JsonValue(state().blink));addChild("quantity",JsonValue(state().quantity.toLong()));addChild("animation",JsonValue(state().animation.toLong()))
                addChild("save",JsonValue(Base64.getEncoder().encodeToString(session().bytes())))
            })
        }
        fun open() {
            approachShop();press(Input.Keys.E)
            while(field<FieldDialogue?>("fieldDialogue")!=null) { idle(80);press(Input.Keys.ENTER) }
            check(scene()=="SHOP" && state().stage==ShopMenu.Stage.HOME)
        }
        fun close() { repeat(4) { if(scene()=="SHOP") press(Input.Keys.ESCAPE) };check(scene()=="FIELD") }
        fun fixture(bytes:ByteArray):DosSave {
            base.copyInto(bytes,92,92,119)
            base.copyInto(bytes,2342,2342,2346)
            bytes[2350]=10;bytes[2351]=0;bytes[2358]=4;bytes[2359]=0
            return DosSave.parse(bytes)
        }
        open();capture("home-buy")
        val frozen=session().bytes();val npcs=field<FieldNpcs>("fieldNpcs").actors.map { listOf(it.x,it.y,it.frame) }
        held+=Input.Keys.D;idle(140);held.clear()
        check(frozen.contentEquals(session().bytes()) && position==(movement().x to movement().y))
        check(npcs==field<FieldNpcs>("fieldNpcs").actors.map { listOf(it.x,it.y,it.frame) })
        val sound=field<com.badlogic.gdx.audio.Music>("openingMusic");check(kotlin.math.abs(sound.volume-101f/127f)<0.001f)
        game.pause();check(!sound.isPlaying);game.resume();check(sound.isPlaying)
        press(Input.Keys.UP);capture("home-sell");press(Input.Keys.UP);capture("home-exit");press(Input.Keys.ENTER)
        check(scene()=="FIELD" && sound.volume==1f)
        val keys=mapOf(1 to Input.Keys.ESCAPE,28 to Input.Keys.ENTER,57 to Input.Keys.SPACE,72 to Input.Keys.UP,80 to Input.Keys.DOWN,75 to Input.Keys.LEFT,77 to Input.Keys.RIGHT)
        for(row in report.get("cases")) {
            val name=row.getString("name");val before=fixture(Base64.getDecoder().decode(row.getString("before")))
            load(before);tick();open()
            if(row.getBoolean("sell")) press(Input.Keys.UP)
            press(Input.Keys.ENTER)
            while(state().item(model,session())!=row.getInt("item")) press(Input.Keys.RIGHT)
            val record=session().bytes()
            val nativeAfter=Base64.getDecoder().decode(row.getString("after"));nativeAfter.copyInto(record,119,119,162)
            val show=name.endsWith("-one") || name.endsWith("-255") || name in listOf("buy-0-poor","buy-0-exact","buy-0-cap","sell-zero","sell-cancel")
            if(show) capture("$name-catalog")
            press(Input.Keys.ENTER)
            if(show) capture("$name-amount")
            for(key in row.get("keys")) {
                press(keys.getValue(key.asInt()))
                repeat(12) { if(state().stage==ShopMenu.Stage.TRADE) {
                    if(show) capture("$name-coin-${state().animation}")
                    // 거래 연출 중 입력으로 중복 구입하거나 취소하지 못한다.
                    press(Input.Keys.ENTER)
                } }
            }
            check(record.contentEquals(session().bytes())) { "실행 거래 불일치: $name" }
            close()
            if(name in listOf("buy-0-one","buy-37-one","sell-31-1","buy-0-native-overdraft")) {
                val money=model.money(session());val count=model.owned(session(),row.getInt("item"))
                saveAndReload();check(money==model.money(session()) && count==model.owned(session(),row.getInt("item")))
            }
        }
        val all=fixture(base.copyOf()).bytes();all.fill(2,123,162)
        load(DosSave.parse(all));tick();open();press(Input.Keys.UP);press(Input.Keys.SPACE)
        check(state().stage==ShopMenu.Stage.CATALOG)
        repeat(39) { i->capture("sell-list-$i");press(Input.Keys.RIGHT) }
        check(state().selected==38);close()
        val empty=fixture(base.copyOf()).bytes();empty.fill(0,123,162)
        load(DosSave.parse(empty));tick();open();press(Input.Keys.UP);press(Input.Keys.ENTER)
        check(state().stage==ShopMenu.Stage.HOME);close()
        // 실제로 구입한 빵 한 개를 사용하고, 그 결과를 저장해 다시 불러온다.
        val use=fixture(base.copyOf()).bytes();use.fill(0,123,162)
        java.nio.ByteBuffer.wrap(use).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(119,100)
        use[352]=1;use[353]=0
        load(DosSave.parse(use));tick();open();press(Input.Keys.ENTER);press(Input.Keys.ENTER);press(Input.Keys.ENTER);idle(12);close()
        check(model.money(session())==85 && model.owned(session(),0)==1)
        press(Input.Keys.ESCAPE);repeat(4) { press(Input.Keys.DOWN) };press(Input.Keys.ENTER);press(Input.Keys.ENTER);press(Input.Keys.ENTER)
        check(model.owned(session(),0)==0)
        repeat(3) { if(scene()!="FIELD") press(Input.Keys.ESCAPE) }
        saveAndReload();check(model.money(session())==85 && model.owned(session(),0)==0)
        Gdx.files.local("$folder/scenes.json").writeString(shots.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        load(DosSave.parse(base))
        println("잡화점 실행 검사: 193 거래·구입 7종·판매 39종·수량/소지금 경계·취소·필드 정지·음량 복귀·저장 재개·구입품 사용, ${shots.size} 화면")
    }
    private fun checkIndoor() {
        val base=session().bytes()
        val shop=field<ShopRenderer>("shopRenderer").model
        val shots=JsonValue(JsonValue.ValueType.array)
        val report=com.badlogic.gdx.utils.JsonReader().parse(Gdx.files.local("docs/indoor-native.json"))
        fun talk(target:Int,label:String,show:Boolean=false) {
            var ready=false
            repeat(12) {
                if(!ready) {
                    approachShop(target);idle(8)
                    val m=movement()
                    ready=m.atRest && field<FieldNpcs>("fieldNpcs").talkTarget(m.x,m.y,m.pose,m.layer)==target
                }
            }
            check(ready) { "실내 주민 접근 실패: ${session().map}/$target" }
            val position=movement().x to movement().y
            press(Input.Keys.E)
            var pages=0;var gold=false
            while(field<FieldDialogue?>("fieldDialogue")!=null) {
                val d=field<FieldDialogue>("fieldDialogue");idle(80);check(d.pageComplete)
                held+=Input.Keys.D;tick();held.clear();press(Input.Keys.ESCAPE)
                check(scene()=="FIELD" && position==(movement().x to movement().y))
                if(show) capture("$label-page-$pages","indoor",shots)
                if(d.isMoneyReward) {
                    gold=true;val money=shop.money(session());check(session().variable(50)==0)
                    idle(140);check(shop.money(session())==money && session().variable(50)==0)
                }
                press(Input.Keys.ENTER);pages++;check(pages<20)
            }
            if(gold) check(session().variable(50)==1)
            check(!field<FieldNpcs>("fieldNpcs").talkReady)
        }
        // 새 게임에서 검 회수까지 이어진 실제 상태로 여비를 받고 물건을 산다.
        route(1);check(session().map==12);route(5);check(session().map==10)
        val money=shop.money(session());check(session().variable(50)==0)
        talk(0,"rondo-first",true);check(shop.money(session())==money+50 && session().variable(50)==1) { "첫 여비: map=${session().map}, group=${session().variable(0)}, event=${session().variable(1)}, money=${shop.money(session())}, flag=${session().variable(50)}" }
        saveAndReload();check(shop.money(session())==money+50 && session().variable(50)==1)
        route(0);check(session().map==12);shopEntry();approachShop();press(Input.Keys.E)
        while(field<FieldDialogue?>("fieldDialogue")!=null) { idle(80);press(Input.Keys.ENTER) }
        check(scene()=="SHOP")
        val bread=shop.owned(session(),0)
        press(Input.Keys.ENTER);press(Input.Keys.ENTER);press(Input.Keys.ENTER);idle(12)
        check(shop.money(session())==money+35 && shop.owned(session(),0)==bread+1)
        repeat(3) { if(scene()=="SHOP") press(Input.Keys.ESCAPE) }
        saveAndReload();check(shop.money(session())==money+35 && session().variable(50)==1)
        route(1);route(5);talk(0,"rondo-repeat",true);check(shop.money(session())==money+35)
        val photographed=mutableSetOf<String>()
        val travel=field<BuildingTravel>("buildingTravel")
        for(row in report.get("cases")) {
            val raw=Base64.getDecoder().decode(row.getString("before"))
            val start=DosSave.parse(raw).travel(12,0,0,88,24,8,0,mapOf(0 to 3))
            val moved=travel.travel(start,mapOf(6 to 7,7 to 8,8 to 4,10 to 5,11 to 6).getValue(row.getInt("map")))!!
            val expected=Base64.getDecoder().decode(row.getString("after"))
            moved.bytes().copyInto(expected,92,92,119)
            moved.bytes().copyInto(expected,2350,2350,2352)
            load(moved);tick()
            val npc=field<FieldNpcs>("fieldNpcs").actors.indexOfFirst { it.event==row.getInt("event") }
            check(npc>=0)
            val label="map-${row.getInt("map")}-npc-${row.getInt("event")}-${row.getString("key")}"
            talk(npc,label,photographed.add(label))
            check(expected.contentEquals(session().bytes())) { "실내 대화 실행 불일치: $label" }
            if(row.getInt("map")==7 && row.getInt("event")==1 && row.getInt("seen")<2) {
                check(session().variable(7)==2);saveAndReload();talk(npc,"joel-repeat")
                check(field<FieldDialogue?>("fieldDialogue")==null && session().variable(7)==2)
            }
        }
        Gdx.files.local("desktop/build/reports/indoor/scenes.json").writeString(shots.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        load(DosSave.parse(base))
        println("실내 대화 실행 검사: 주민 8명·224분기·첫 여비 50→구입 후 35·저장 재개·반복 무지급·대화 중 필드 잠금, ${shots.size} 화면")
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
        if(equipment) checkEquipment()
        if(partyEquipment) checkPartyEquipment()
        if(automaticEquipment) checkAutomaticEquipment()
        if(fieldItems) checkFieldItems()
        if(tentEnabled) checkTent()
        if(shopEnabled) checkShop()
        if(indoorEnabled) checkIndoor()
        Gdx.files.local("desktop/build/reports/sword/scenes.json").writeString(captures.toJson(JsonWriter.OutputType.json),false,"UTF-8")
        println("검 수리 스토리 검사 완료: $steps 걸음, 진행 0→1→2→3→4, 합류·쪽지 각 1회, 맡기기·회수·중복 방지·저장 재개, ${captures.size} 화면")
        completed=true;Gdx.app.exit()
    }
    override fun dispose() { game.dispose() }
}
