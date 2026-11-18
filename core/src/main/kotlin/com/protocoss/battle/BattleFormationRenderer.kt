package com.protocoss.battle

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable

/** 배경과 적 편성만 그린다. 파티·명령창·진입 연출은 후속 전투 화면에서 합성한다. */
class BattleFormationRenderer : Disposable {
    private val textures = mutableMapOf<String,Texture>()
    private fun texture(path: String) = textures.getOrPut(path) {
        Texture(Gdx.files.internal(path)).apply { setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest) }
    }
    fun draw(batch: SpriteBatch, formation: BattleEntry.Formation) {
        batch.draw(texture("assets/backgrounds/battle_back_%02d.png".format(formation.background)),0f,0f,320f,200f)
        for (enemy in formation.enemies) {
            val picture = texture("assets/battle/monster-${enemy.monster}.png")
            batch.draw(picture,enemy.x.toFloat(),200f-enemy.y-picture.height)
        }
    }
    override fun dispose() { textures.values.forEach { it.dispose() };textures.clear() }
}
