package com.protocoss.battle

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable

/** 시작 파티의 원작 그림과 명령 안내 레이어. 상태창·피해/공격 연출은 별도 복원한다. */
class BattlePartyRenderer(private val party:BattleParty):Disposable {
    private val textures=mutableMapOf<String,Texture>()
    private fun texture(path:String)=textures.getOrPut(path) {
        Texture(Gdx.files.internal(path)).apply { setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest) }
    }
    fun draw(batch:SpriteBatch,actors:List<BattleParty.Actor>,guide:Boolean=false) {
        for(layer in party.layers(actors)) {
            val t=texture("assets/sprites/user/character_%02d/frame_%03d.png".format(layer.character,layer.frame))
            batch.draw(t,layer.x.toFloat(),200f-layer.y-t.height)
        }
        if(guide) {
            val t=texture("assets/battle/command-guide.png")
            batch.draw(t,11f,200f-13-t.height)
        }
    }
    override fun dispose() { textures.values.forEach { it.dispose() };textures.clear() }
}
