package com.protocoss.opening

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.JsonValue
import kotlin.math.floor
import com.protocoss.field.FieldNpcs

/** 위에서 아래로 기록된 원작 좌표를 320×200 화면에 그린다. 필드와 전투 그림을 구분한다. */
class OpeningRenderer(val timeline: OpeningTimeline) : Disposable {
    private val data = timeline.data
    private val textures = mutableListOf<Texture>()
    private fun texture(name: String) = Texture(Gdx.files.internal("assets/opening/$name")).also {
        it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest); textures += it
    }
    private val mapNumbers = data.get("maps").map { it.name.toInt() }
    private val people = texture("people.png")
    private val darkPeople = mapNumbers.associateWith { texture("people-dialogue-$it.png") }
    private val darkFronts = mapNumbers.associateWith { texture("fronts-dialogue-$it.png") }
    private val darkOverlays = mapNumbers.associateWith { texture("overlays-dialogue-$it.png") }
    private var dialoguePass = false
    private var clipBottom=26f
    private var clipTop=66f
    private var passMap=0
    private val font = texture("font.png")
    private val portraits = mapOf(0 to texture("portrait-0.png"), 1 to texture("portrait-1.png"), 17 to texture("portrait-17.png"))
    private val tiles = mapNumbers.associateWith { texture("tiles-$it.png") }
    private val darkTiles = mapNumbers.associateWith { texture("tiles-dialogue-$it.png") }
    private val dialogueBackgrounds = mapNumbers.associateWith { texture("dialogue-background-$it.png") }
    private val fronts = mapNumbers.associateWith { texture("fronts-$it.png") }
    private val overlays = mapNumbers.associateWith { texture("overlays-$it.png") }
    private val spriteRows = data.get("sprites").mapIndexed { index, value -> value.asInt() to index }.toMap()
    private val characters = data.get("font").getString("characters")
    private val glyphs = characters.mapIndexed { index, c -> c to TextureRegion(font,index%16*16,index/16*16,
        data.get("font").get("widths").getInt(c.toString()),16) }.toMap()
    private val mapTicks = mutableMapOf<Int, Int>()
    private val cells = mapNumbers.associateWith { number ->
        data.get("maps").get(number.toString()).get("cells").map { it[0].asInt() }.toIntArray()
    }
    private var animationAccumulator = 0f
    private var animationTick = 0

    private val black = run {
        val pixmap=com.badlogic.gdx.graphics.Pixmap(1,1,com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE);pixmap.fill()
        try { Texture(pixmap).also { textures+=it } } finally { pixmap.dispose() }
    }
    fun fade(batch: SpriteBatch, alpha: Float) {
        if(alpha<=0f) return
        batch.color=Color(0f,0f,0f,alpha.coerceIn(0f,1f));batch.draw(black,0f,0f,320f,200f);batch.color=Color.WHITE
    }

    fun update(delta: Float) {
        animationAccumulator += delta
        while (animationAccumulator >= .12f) { animationAccumulator -= .12f; animationTick++ }
    }

    private fun map(number: Int): JsonValue = data.get("maps").get(number.toString())
    private fun tile(batch: SpriteBatch, texture: Texture, id: Int, x: Float, y: Float, size: Float = 8f) {
        val bottom=if(dialoguePass) maxOf(y,clipBottom) else y
        val top=if(dialoguePass) minOf(y+size,clipTop) else y+size
        if(top<=bottom) return
        batch.draw(texture,x,bottom,size,top-bottom,id%40*8,id/40*8+(y+size-top).toInt(),8,(top-bottom).toInt(),false,false)
    }

    fun render(batch: SpriteBatch) {
        renderMap(batch,timeline.map,timeline.scrollX,timeline.scrollY)
        val sprites = data.get("programs")[timeline.program].get("sprites")
        timeline.actors.forEach { actor ->
            renderActor(batch, sprites[actor.sprite].asInt(), actor.frame,
                actor.x-timeline.scrollX, actor.y-timeline.scrollY)
            coverActor(batch,timeline.map,actor.x,actor.y,timeline.scrollX,timeline.scrollY,actor.mask)
        }
        timeline.page?.let { page ->
            // 원작은 화면 (0,134)..(320,174)에 배경을 어둡게 비친다.
            batch.draw(dialogueBackgrounds.getValue(timeline.map),0f,26f,320f,40f,
                timeline.scrollX.toInt(),timeline.scrollY.toInt()+134,320,40,false,false)
            dialoguePass=true;passMap=timeline.map
            timeline.actors.forEach { actor ->
                renderActor(batch,sprites[actor.sprite].asInt(),actor.frame,actor.x-timeline.scrollX,actor.y-timeline.scrollY)
                coverActor(batch,timeline.map,actor.x,actor.y,timeline.scrollX,timeline.scrollY,actor.mask)
            }
            dialoguePass=false
            renderDialogueText(batch,page,timeline.visibleCharacters)
        }
        fade(batch,timeline.fadeAlpha)
    }

    fun renderPlaceBanner(batch:SpriteBatch,number:Int,sx:Float,sy:Float,x:Float,name:String,
                          heroX:Float,heroY:Float,pose:Int,layer:Int,npcs:List<FieldNpcs.Actor>?=null) {
        dialoguePass=true;passMap=number;clipBottom=176f;clipTop=196f
        renderMap(batch,number,sx,sy)
        if(npcs!=null) npcs.forEach { npc ->
            renderActor(batch,npc.sprite,npc.frame,npc.x-sx,npc.y-sy)
            coverActor(batch,number,npc.x.toFloat(),npc.y.toFloat(),sx,sy,npc.mask)
        } else map(number).get("npcs").forEach { npc ->
            renderActor(batch,npc.getInt("sprite"),npc.getInt("frame"),npc.getFloat("x")-sx,npc.getFloat("y")-sy)
            coverActor(batch,number,npc.getFloat("x"),npc.getFloat("y"),sx,sy,npc.getInt("mask"))
        }
        renderActor(batch,0,pose,heroX-sx,heroY-sy)
        coverActor(batch,number,heroX,heroY,sx,sy,layer)
        dialoguePass=false;clipBottom=26f;clipTop=66f
        text(batch,name,x,7f,Color(252f/255f,252f/255f,252f/255f,1f))
    }

    fun renderFieldDialogue(batch:SpriteBatch,number:Int,sx:Float,sy:Float,npcs:List<FieldNpcs.Actor>,
                            heroX:Float,heroY:Float,pose:Int,layer:Int,page:JsonValue,visible:Int) {
        dialoguePass=true;passMap=number
        renderMap(batch,number,sx,sy)
        npcs.forEach { npc ->
            renderActor(batch,npc.sprite,npc.frame,npc.x-sx,npc.y-sy)
            coverActor(batch,number,npc.x.toFloat(),npc.y.toFloat(),sx,sy,npc.mask)
        }
        renderActor(batch,0,pose,heroX-sx,heroY-sy)
        coverActor(batch,number,heroX,heroY,sx,sy,layer)
        dialoguePass=false
        renderDialogueText(batch,page,visible)
    }

    private fun renderDialogueText(batch:SpriteBatch,page:JsonValue,visible:Int) {
        val portrait=portraits[page.getInt("portrait")]
        if(portrait!=null) batch.draw(portrait,14f,200f-62f-portrait.height,portrait.width.toFloat(),portrait.height.toFloat())
        text(batch,page.getString("speaker"),if(portrait==null)14f else 94f,118f,Color(252f/255f,252f/255f,0f,1f))
        text(batch,page.getString("text").take(visible),14f,139f,Color(252f/255f,252f/255f,252f/255f,1f))
    }

    fun resetFieldMaps() {
        cells.forEach { (number,values) -> map(number).get("cells").forEachIndexed { index,value -> values[index]=value[0].asInt() } }
        cells.keys.forEach { mapTicks[it]=animationTick }
    }
    fun openCabinDoor(changes: JsonValue) {
        changes.forEach { cells.getValue(0)[it.getInt("index")]=it.getInt("tid") }
    }
    fun renderMap(batch: SpriteBatch, number: Int, sx: Float, sy: Float, fullMap: Boolean = false) {
        val m = map(number); val width = m.getInt("width"); val height = m.getInt("height")
        val current = cells.getValue(number)
        if (mapTicks[number] != animationTick) {
            repeat((animationTick-(mapTicks[number] ?: 0)).coerceAtLeast(0)) {
                m.get("animations").forEach { rect ->
                    for(y in rect[1].asInt() until rect[1].asInt()+rect[3].asInt())
                        for(x in rect[0].asInt() until rect[0].asInt()+rect[2].asInt()) {
                            val i = y*width+x
                            current[i] = (current[i] and 0xf000) or (m.get("next_tiles")[current[i] and 4095].asInt() and 4095)
                        }
                }
            }
            mapTicks[number] = animationTick
        }
        for (y in (if(fullMap) 0 else floor(sy/8).toInt().coerceAtLeast(0))..(if(fullMap) height-1 else ((sy+199)/8).toInt().coerceAtMost(height-1)))
            for(x in (if(fullMap) 0 else floor(sx/8).toInt().coerceAtLeast(0))..(if(fullMap) width-1 else ((sx+319)/8).toInt().coerceAtMost(width-1)))
                tile(batch,(if(dialoguePass) darkTiles else tiles).getValue(number),current[y*width+x] and 4095,x*8f-sx,192f-y*8f+sy)
    }

    fun renderActor(batch: SpriteBatch, sprite: Int, frame: Int, x: Float, topY: Float) {
        val row = spriteRows[sprite] ?: return
        val source = data.get("frame_map")[frame.mod(16)].asInt()
        val y=176f-topY
        val bottom=if(dialoguePass) maxOf(y,clipBottom) else y
        val top=if(dialoguePass) minOf(y+24,clipTop) else y+24
        if(top<=bottom) return
        batch.draw(if(dialoguePass) darkPeople.getValue(passMap) else people,x,bottom,24f,top-bottom,
            source*24,row*24+(y+24-top).toInt(),24,(top-bottom).toInt(),false,false)
    }

    fun coverActor(batch: SpriteBatch, number: Int, x: Float, y: Float, sx: Float, sy: Float, mask: Int) {
        val m = map(number); val width=m.getInt("width"); val height=m.getInt("height")
        val original=m.get("cells"); val current=cells.getValue(number)
        val topRow=((y+31)/8).toInt()-1
        val rows=if(((y+31)/8).toInt()==((y+24)/8).toInt()) 2 else 3
        val leftColumn=(x/8).toInt()
        val columns=if((x/8).toInt()==((x+7)/8).toInt()) 3 else 4
        for(ty in topRow-rows..topRow) for(tx in leftColumn until leftColumn+columns) {
            if(tx !in 0 until width || ty !in 0 until height) continue
            val cell=original[ty*width+tx]
            val bottom=ty==topRow
            val flag=when(mask) {
                0 -> cell[0].asInt() and (if(bottom) 0x4000 else 0x2000) != 0
                1 -> cell[1].asInt() and (if(bottom) 128 else 64) != 0
                2 -> cell[1].asInt() and (if(bottom) 1024 else 512) != 0
                else -> false
            }
            if(flag) tile(batch,(if(dialoguePass) { if(bottom) darkOverlays else darkFronts } else { if(bottom) overlays else fronts }).getValue(number),current[ty*width+tx] and 4095,tx*8f-sx,192f-ty*8f+sy)
        }
    }

    private fun text(batch: SpriteBatch, value: String, x: Float, topY: Float, color: Color) {
        var cx=x; var cy=topY
        batch.color=color
        value.forEach { c -> if(c=='\n') { cx=x;cy+=16f } else {
            val glyph=glyphs[c] ?: error("원작 시작 대화의 글꼴 누락: $c")
            // 원작 이름은 오른쪽·아래에 검은 그림자를 그린다.
            if(color.b == 0f) { batch.color=Color.BLACK; batch.draw(glyph,cx+1,183f-cy);batch.color=color }
            batch.draw(glyph,cx,184f-cy);cx+=glyph.regionWidth
        } }
        batch.color=Color.WHITE
    }

    override fun dispose() { textures.forEach { it.dispose() } }
}
