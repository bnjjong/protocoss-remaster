package com.protocoss.menu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave

/** 상점은 정지한 필드 위에 원작 색 번호 변환과 메뉴 그림을 합성한다. */
class ShopRenderer:Disposable {
    val model=Shop(JsonReader().parse(Gdx.files.internal("assets/ui/shop/shop.json").readString("UTF-8")))
    private val atlas=Pixmap(Gdx.files.internal("assets/ui/shop/atlas.png"))
    private val buffer=FrameBuffer(Pixmap.Format.RGBA8888,320,200,false)
    private val field=Pixmap(320,200,Pixmap.Format.RGBA8888)
    private val canvas=Pixmap(320,200,Pixmap.Format.RGBA8888)
    private val texture=Texture(320,200,Pixmap.Format.RGBA8888).apply { setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest) }
    private val palette=model.data.get("palette").asIntArray()
    private val shade=model.data.get("shade").asIntArray()
    private val colors=(0..255).map { i->(palette[i*3] shl 24) or (palette[i*3+1] shl 16) or (palette[i*3+2] shl 8) or 255 }
    private val indices=colors.withIndex().associate { it.value to it.index }
    private var last=""
    var needsCapture=true;private set
    init { field.setBlending(Pixmap.Blending.None);canvas.setBlending(Pixmap.Blending.None) }
    fun begin() { needsCapture=true;last="" }
    fun beginField() { buffer.begin();Gdx.gl.glClearColor(0f,0f,0f,1f);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT) }
    fun endField() {
        val raw=Pixmap.createFromFrameBuffer(0,0,320,200)
        for(y in 0 until 200) for(x in 0 until 320) field.drawPixel(x,y,raw.getPixel(x,199-y))
        raw.dispose();buffer.end();needsCapture=false;last=""
    }
    private fun image(name:String,x:Int,y:Int,mask:Boolean=false) {
        val r=model.data.get("rects").get(name).asIntArray()
        for(py in 0 until r[3]) for(px in 0 until r[2]) {
            val c=atlas.getPixel(r[0]+px,r[1]+py)
            if(c and 255!=0 && x+px in 0..319 && y+py in 0..199) {
                if(mask) {
                    val index=indices[canvas.getPixel(x+px,y+py)] ?: error("원작 팔레트 밖의 상점 배경색")
                    canvas.drawPixel(x+px,y+py,colors[shade[index]])
                } else canvas.drawPixel(x+px,y+py,c)
            }
        }
    }
    private fun text(value:String,x:Int,y:Int) {
        var left=x;var top=y
        for(c in value) {
            if(c=='/' || c=='\n') { left=x;top+=16;continue }
            val r=model.data.get("rects").get("glyph-${c.code}").asIntArray()
            for(py in 0 until r[3]) for(px in 0 until r[2]) if(atlas.getPixel(r[0]+px,r[1]+py) and 255!=0) canvas.drawPixel(left+px+1,top+py+1,255)
            for(py in 0 until r[3]) for(px in 0 until r[2]) if(atlas.getPixel(r[0]+px,r[1]+py) and 255!=0) canvas.drawPixel(left+px,top+py,colors[211])
            left+=r[2]
        }
    }
    private fun number(value:Int,x:Int,y:Int,right:Boolean=true) {
        val s=value.toString();var left=if(right) x-s.length*8 else x
        for(c in s) {
            // 원작은 음수 기호 그림을 초기화하지 않는다. 여기서는 같은 원작 글꼴로 표시한다.
            if(c=='-') text("-",left,y) else image("imenu-${c-'0'+17}",left,y)
            left+=8
        }
    }
    private fun money(save:DosSave,step:Int=0) {
        val value=model.money(save);val half=value.toString().length*4
        number(value,159-half,26,false)
        val dx=intArrayOf(0,0,0,0,0,0,0,1,0,2,2,0)[step];val dy=intArrayOf(0,0,0,0,0,0,0,4,5,4,3,2)[step]
        image("imenu-${53+step}",160+half-dx,25-dy)
    }
    fun render(batch:SpriteBatch,save:DosSave,state:ShopMenu) {
        val key="${state.stage}/${state.choice}/${state.selected}/${state.quantity}/${state.animation}/${state.blink}/${model.money(save)}/${save.bytes().slice(123..161)}"
        if(last!=key) {
            canvas.drawPixmap(field,0,0)
            image("imenu-15",115,11,true);image("imenu-9",103,1);image("imenu-${state.choice}",118,40)
            if(state.stage!=ShopMenu.Stage.HOME) {
                image("imenu-16",33,120,true);image("imenu-10",0,107)
                val choices=model.candidates(save,state.sell);val id=choices[state.selected];val item=model.data.get("items")[id]
                if(state.stage==ShopMenu.Stage.CATALOG) {
                    for(i in -2..2) {
                        val j=state.selected+i
                        if(j in choices.indices) {
                            val r=model.data.get("items")[choices[j]]
                            image("common-${r.getInt("icon")}${if(i!=0) "-muted" else ""}",153+i*31,133)
                        }
                    }
                    val name=item.getString("name");text(name,159-name.toByteArray(java.nio.charset.Charset.forName("x-Johab")).size*4,153)
                    number(model.price(id,state.sell),234,170);image("imenu-53",235,169)
                    val left=state.selected>2 && !state.blink
                    image("imenu-${if(left)12 else 11}",if(left)72 else 73,133)
                    val right=state.selected+2<choices.lastIndex && !state.blink
                    image("imenu-${if(right)14 else 13}",if(right)239 else 238,133)
                } else {
                    image("common-${item.getInt("icon")}",81,133)
                    val step=if(state.stage==ShopMenu.Stage.TRADE) state.animation.coerceAtMost(11) else 0
                    val dx=intArrayOf(0,0,0,0,0,0,0,1,0,2,2,0)[step]
                    val dy=intArrayOf(0,0,0,0,0,0,0,4,5,4,3,2)[step]
                    number(state.quantity,234,129);number(state.quantity*model.price(id,state.sell),234,141);image("imenu-${53+step}",235-dx,140-dy)
                    text(item.getString("description"),73,153)
                    val total=state.quantity*model.price(id,state.sell)
                    number(model.money(save)+if(state.sell) total else -total,234,170);image("imenu-${53+step}",235-dx,169-dy)
                }
                number(model.owned(save,id),124,170)
            }
            money(save,if(state.stage==ShopMenu.Stage.TRADE) state.animation.coerceAtMost(11) else 0)
            texture.draw(canvas,0,0);last=key
        }
        batch.begin();batch.draw(texture,0f,0f,320f,200f);batch.end()
    }
    override fun dispose() { atlas.dispose();buffer.dispose();field.dispose();canvas.dispose();texture.dispose() }
}
