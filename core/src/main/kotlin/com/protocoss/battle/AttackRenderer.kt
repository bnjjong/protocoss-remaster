package com.protocoss.battle

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.JsonReader

/** 공격의 파티·효과·피격·숫자 레이어만 그린다. 전체 전투 상태창·음악은 별도 연결한다. */
class AttackRenderer(json:String):Disposable {
    private val data=JsonReader().parse(json)
    private val textures=mutableMapOf<String,Texture>()
    private fun texture(file:String)=textures.getOrPut(file) {
        Texture(Gdx.files.internal(file)).apply { setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest) }
    }
    private fun draw(batch:SpriteBatch,d:BasicAttack.Draw) {
        if(d.kind=="sprite") {
            val key="${d.sprite}-s${d.shade}-t${d.table}"
            val row=data.get("images").get(key)
            val t=texture(row.getString("file"))
            batch.draw(t,d.x.toFloat(),200f-d.y-t.height)
        } else {
            val font=texture("assets/battle/attack/font.png");val chars=data.getString("characters")
            for((i,ch) in d.text.withIndex()) {
                val position=chars.indexOf(ch);require(position>=0)
                val glyph=TextureRegion(font,position*8,0,8,16)
                batch.color=Color.BLACK;batch.draw(glyph,d.x+i*8+1f,200f-d.y-17)
                batch.color=Color.WHITE;batch.draw(glyph,d.x+i*8f,200f-d.y-16)
            }
        }
    }
    fun draw(batch:SpriteBatch,attack:BasicAttack,formation:BattleEntry.Formation,frame:BasicAttack.Frame) {
        batch.color=Color.WHITE
        batch.draw(texture("assets/backgrounds/battle_back_%02d.png".format(formation.background)),0f,0f,320f,200f)
        if(frame.draws.none { it.sprite.startsWith("monster-") })
            for((i,e) in formation.enemies.withIndex())if(attack.status[i] !in listOf(0,5))draw(batch,BasicAttack.Draw("sprite","monster-${e.monster}",e.x,e.y))
        for(d in attack.userDraws(frame.frame))draw(batch,d)
        for(d in frame.draws)draw(batch,d)
    }
    override fun dispose() { textures.values.forEach { it.dispose() };textures.clear() }
}
