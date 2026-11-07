package com.protocoss.menu

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.protocoss.dialogue.FontManager

/** 원작 타이틀 두 선택 상태와 저장 목록·일시 정지 메뉴를 그린다. */
class GameMenu(private val fonts: FontManager) : Disposable {
    private val titleTextures = listOf("new-game", "continue").map {
        Texture("assets/ui/title/$it.png").apply { setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
    }
    private val shapes = ShapeRenderer()
    private val gold = Color(0.92f, 0.78f, 0.43f, 1f)

    fun title(batch: SpriteBatch, selected: Int) {
        batch.begin()
        batch.color = Color.WHITE
        batch.draw(titleTextures[selected], 0f, 0f, 320f, 200f)
        batch.end()
    }

    fun list(batch: SpriteBatch, projection: Matrix4, title: String, rows: List<String>, selected: Int, message: String) {
        shapes.projectionMatrix = projection
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color(0.025f, 0.035f, 0.065f, 1f)
        shapes.rect(0f, 0f, 320f, 200f)
        shapes.color = Color(0.10f, 0.14f, 0.22f, 1f)
        shapes.rect(8f, 154f - selected * 16f, 304f, 16f)
        shapes.end()
        val font = fonts.getFont(11)
        batch.begin()
        font.color = gold
        font.draw(batch, title, 12f, 192f)
        rows.forEachIndexed { index, row ->
            font.color = if (index == selected) gold else Color.LIGHT_GRAY
            font.draw(batch, (if (index == selected) "▶ " else "  ") + row, 12f, 168f - index * 16f)
        }
        font.color = Color.LIGHT_GRAY
        val layout = GlyphLayout()
        layout.setText(font, message, Color.LIGHT_GRAY, 296f, com.badlogic.gdx.utils.Align.left, true)
        font.draw(batch, layout, 12f, 35f)
        font.color = Color.WHITE
        batch.end()
    }

    override fun dispose() {
        titleTextures.forEach { it.dispose() }
        shapes.dispose()
    }
}
