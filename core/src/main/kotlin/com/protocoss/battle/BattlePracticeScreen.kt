package com.protocoss.battle

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.protocoss.dialogue.FontManager

class BattlePracticeScreen(private val exitLabel: String, private val onExit: () -> Unit) : ApplicationAdapter() {
    private val viewport = FitViewport(720f, 420f)
    private val fonts = FontManager()
    private val background = Color.valueOf("121A25")
    private val panel = Color.valueOf("202C3D")
    private val barTrack = Color.valueOf("354256")
    private val playerColor = Color.valueOf("63D7B4")
    private val enemyColor = Color.valueOf("F3947F")
    private val hintColor = Color.valueOf("BCC9D8")

    private lateinit var batch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var heading: BitmapFont
    private lateinit var label: BitmapFont
    private lateinit var body: BitmapFont
    private var battle = PracticeBattle()
    private var lastTurn: PracticeBattle.Turn? = null

    override fun create() {
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        heading = fonts.getFont(29)
        label = fonts.getFont(19)
        body = fonts.getFont(16).apply { color = hintColor }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render() {
        when {
            Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) -> {
                onExit()
                return
            }
            Gdx.input.isKeyJustPressed(Input.Keys.R) -> {
                battle = PracticeBattle()
                lastTurn = null
            }
            Gdx.input.isKeyJustPressed(Input.Keys.A) -> takeTurn(PracticeBattle.Action.ATTACK)
            Gdx.input.isKeyJustPressed(Input.Keys.G) -> takeTurn(PracticeBattle.Action.GUARD)
        }

        ScreenUtils.clear(background)
        viewport.apply()
        shapes.projectionMatrix = viewport.camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        drawHealthBar(36f, battle.playerHealth, PracticeBattle.PLAYER_MAX_HEALTH, playerColor)
        drawHealthBar(372f, battle.enemyHealth, PracticeBattle.ENEMY_MAX_HEALTH, enemyColor)
        shapes.end()

        batch.projectionMatrix = viewport.camera.combined
        batch.begin()
        heading.draw(batch, "연습 전투", 36f, 386f)
        label.draw(batch, "플레이어  ${battle.playerHealth} / ${PracticeBattle.PLAYER_MAX_HEALTH}", 52f, 277f)
        label.draw(batch, "연습 상대  ${battle.enemyHealth} / ${PracticeBattle.ENEMY_MAX_HEALTH}", 388f, 277f)
        label.draw(batch, when (battle.state) {
            PracticeBattle.State.ACTIVE -> "${battle.round}번째 턴 · 상대의 다음 공격 피해 ${battle.nextEnemyDamage}"
            PracticeBattle.State.WON -> "승리했습니다"
            PracticeBattle.State.LOST -> "패배했습니다"
        }, 36f, 173f)

        val log = lastTurn?.let { "준 피해 ${it.damageDealt} · 받은 피해 ${it.damageTaken}" }
            ?: "방어하면 받는 피해가 4분의 1로 줄어듭니다."
        body.draw(batch, log, 36f, 135f)
        val controls = if (battle.state == PracticeBattle.State.ACTIVE) {
            "A 공격    G 방어    R 다시 시작    Esc $exitLabel"
        } else "R 다시 시작    Esc $exitLabel"
        body.draw(batch, controls, 36f, 55f)
        batch.end()
    }

    override fun dispose() {
        fonts.dispose()
        batch.dispose()
        shapes.dispose()
    }

    private fun takeTurn(action: PracticeBattle.Action) {
        battle.act(action)?.let { lastTurn = it }
    }

    private fun drawHealthBar(x: Float, health: Int, maxHealth: Int, color: Color) {
        shapes.color = panel
        shapes.rect(x, 209f, 312f, 101f)
        shapes.color = barTrack
        shapes.rect(x + 16f, 232f, 280f, 12f)
        shapes.color = color
        shapes.rect(x + 16f, 232f, 280f * health / maxHealth, 12f)
    }
}
