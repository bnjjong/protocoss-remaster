package com.protocoss.menu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.JsonReader
import com.protocoss.save.DosSave

/** IMENU·COMMON과 원작 조합 글꼴을 320×200 메뉴 좌표에 그린다. */
class EquipmentRenderer:Disposable {
    val model=Equipment(JsonReader().parse(Gdx.files.internal("assets/ui/equipment/equipment.json").readString("UTF-8")))
    private val atlas=Texture(Gdx.files.internal("assets/ui/equipment/atlas.png")).apply { setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest) }
    private val regions=model.data.get("rects").associate { it.name to TextureRegion(atlas,it[0].asInt(),it[1].asInt(),it[2].asInt(),it[3].asInt()) }
    private val palette=model.data.get("palette").asIntArray()
    private fun image(batch:SpriteBatch,name:String,x:Int,y:Int) {
        val region=regions.getValue(name);batch.draw(region,x.toFloat(),(200-y-region.regionHeight).toFloat())
    }
    private fun text(batch:SpriteBatch,value:String,x:Int,y:Int,color:Int=211) {
        var left=x;var top=y
        for(c in value) {
            if(c=='/' || c=='\n') { left=x;top+=16;continue }
            if(left>=320) break
            val glyph=regions.getValue("glyph-${c.code}")
            batch.color=Color.BLACK;batch.draw(glyph,left+1f,183f-top)
            batch.color=Color(palette[color*3]/255f,palette[color*3+1]/255f,palette[color*3+2]/255f,1f)
            batch.draw(glyph,left.toFloat(),184f-top);left+=glyph.regionWidth
        }
        batch.color=Color.WHITE
    }
    private fun number(batch:SpriteBatch,value:Int,x:Int,y:Int,right:Boolean=true,muted:Boolean=false) {
        val str=value.toString();var left=if(right) x-str.length*8 else x
        for(c in str) { image(batch,"imenu-${c-'0'+17}${if(muted) "-muted" else ""}",left,y);left+=8 }
    }
    fun weapon(batch:SpriteBatch,save:DosSave,state:EquipmentMenu) {
        batch.begin();image(batch,"imenu-96",0,0);image(batch,"imenu-232",0,0)
        image(batch,if(state.character==0) "kaman-6" else "person-${state.character}-6",1,36)
        val slots=listOf(44 to 40,44 to 76,14 to 89,75 to 89,18 to 120,70 to 120)
        val bytes=save.bytes()
        for(slot in slots.indices) {
            val id=bytes[473+153*state.character+slot].toInt() and 255
            if(id in model.weapons.indices) {
                val (x,y)=slots[slot];val offset=if(id>=163)4 else 0
                image(batch,"common-${model.weapons[id].icon}",x+offset,y+offset)
            }
        }
        val values=model.stats(save,state.character);val order=intArrayOf(0,1,2,3,5,4)
        order.forEachIndexed { index,j -> number(batch,values[j],if(index<3)105 else 270,157+index%3*14) }
        if(state.stage==EquipmentMenu.Stage.HOME) { image(batch,"imenu-233",46,19) }
        else {
            val cursor=model.data.get("slot_cursors")[state.slot]
            image(batch,"imenu-236",cursor[0].asInt(),cursor[1].asInt())
        }
        if(state.stage==EquipmentMenu.Stage.SELECT) {
            val choices=model.candidates(save,state.character,state.slot)
            if(choices.isEmpty()) text(batch,model.data.getString("empty_weapon"),134,84)
            else {
                val preview=model.preview(save,choices[state.selected].id,state.character,state.slot)
                order.forEachIndexed { index,j -> number(batch,preview[j],if(index<3)119 else 284,157+index%3*14,false,preview[j]<=values[j]) }
                val row=state.selected-state.scroll
                batch.draw(TextureRegion(regions.getValue("weapon-highlight"),120,47+row*24,188,18),120f,(200-47-row*24-18).toFloat())
                choices.drop(state.scroll).take(4).forEachIndexed { i,item ->
                    val y=44+i*24;image(batch,"common-${item.icon}",128,y+if(item.id>=163)5 else 0);text(batch,item.name,160,y+4)
                    number(batch,model.quantity(save,item.id,true),303,y+7)
                }
            }
        }
        batch.end()
    }
    fun inventory(batch:SpriteBatch,save:DosSave,equipment:Boolean,selected:Int,scroll:Int) {
        batch.begin();image(batch,"imenu-96",0,0);image(batch,"imenu-231",0,0)
        text(batch,if(equipment) "장비" else "소지품",14,16)
        val items=model.inventory(save,equipment)
        if(items.isEmpty()) text(batch,model.data.getString("empty_items"),24,169)
        else {
            val row=selected-scroll
            batch.draw(TextureRegion(regions.getValue("item-highlight"),32,37+row*18,258,10),32f,(200-37-row*18-10).toFloat())
            items.drop(scroll).take(6).forEachIndexed { i,item ->
                val y=34+i*18;val muted=!equipment && item.category !in listOf(0,2,3)
                image(batch,"common-${item.icon}${if(muted) "-muted" else ""}",44,y);text(batch,item.name,69,y,if(muted)215 else 211)
                number(batch,model.quantity(save,item.id,equipment),269,y+3,muted=muted)
            }
            if(!equipment) text(batch,items[selected].description,24,169)
        }
        batch.end()
    }
    override fun dispose()=atlas.dispose()
}
