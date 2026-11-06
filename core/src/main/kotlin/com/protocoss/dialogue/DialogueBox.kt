package com.protocoss.dialogue

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable

/**
 * 1995년 고전 DOS RPG 감성의 320×200 정수 픽셀 퍼펙트 대화창 UI 컴포넌트입니다.
 *
 * 짙은 남색 반투명 윈도우 스킨과 골드 듀얼 테두리, 화자 이름 명패, 초상화 슬롯 및
 * 깜빡이는 다음 페이지(`▼`) 인디케이터를 렌더링합니다.
 */
class DialogueBox(
    private val fontManager: FontManager
) : Disposable {

    private val shapeRenderer = ShapeRenderer()
    private var blinkTimer: Float = 0f
    private var defaultAvatarTexture: Texture? = null

    // 대화창 레이아웃 상수 (320×200 기준)
    private val boxX = 8f
    private val boxY = 8f
    private val boxW = 304f
    private val boxH = 58f

    // 초상화 영역
    private val portraitX = 14f
    private val portraitY = 14f
    private val portraitSize = 46f

    // 텍스트 영역
    private val textStartX = 66f
    private val textStartY = 52f

    // 명패 영역
    private val nameplateX = 12f
    private val nameplateY = 62f
    private val nameplateH = 14f

    init {
        // 기본 아바타로 카만 테스트 도트 활용
        val kamanFile = Gdx.files.internal("assets/sprites/kaman_test.png")
        if (kamanFile.exists()) {
            defaultAvatarTexture = Texture(kamanFile)
        }
    }

    /**
     * 프레임 경과에 따른 인디케이터 깜빡임 타이머를 갱신합니다.
     */
    fun update(delta: Float) {
        blinkTimer += delta
    }

    /**
     * 현재 [dialogueManager]의 대화 상태에 맞춰 대화창을 화면에 렌더링합니다.
     *
     * @param batch 스프라이트 및 텍스트 렌더링용 [SpriteBatch]
     * @param dialogueManager 현재 대화 상태를 관리하는 [DialogueManager]
     * @param customPortrait 특정 화자에 대한 사용자 정의 텍스처 (선택 사항)
     */
    fun render(
        batch: SpriteBatch,
        dialogueManager: DialogueManager,
        customPortrait: Texture? = null
    ) {
        if (!dialogueManager.isActive) return

        val page = dialogueManager.currentPage ?: return
        val font = fontManager.getFont(11)

        // 1단계: 셰이프 렌더러로 반투명 배경 및 골드 프레임 렌더링
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapeRenderer.projectionMatrix = batch.projectionMatrix
        renderWindowFrame(page.speakerName)

        Gdx.gl.glDisable(GL20.GL_BLEND)

        // 2단계: 스프라이트 배치로 초상화, 화자 이름 및 텍스트 렌더링
        batch.begin()

        // 초상화 렌더링
        val avatar = customPortrait ?: defaultAvatarTexture
        if (avatar != null) {
            // 초상화 영역 안에 알맞게 중앙 정렬하여 렌더링
            val aspect = avatar.width.toFloat() / avatar.height.toFloat()
            val drawW: Float
            val drawH: Float
            if (aspect >= 1f) {
                drawW = portraitSize - 4f
                drawH = drawW / aspect
            } else {
                drawH = portraitSize - 4f
                drawW = drawH * aspect
            }
            val drawX = portraitX + (portraitSize - drawW) / 2f
            val drawY = portraitY + (portraitSize - drawH) / 2f
            batch.draw(avatar, drawX, drawY, drawW, drawH)
        }

        // 화자 이름 렌더링
        if (page.speakerName.isNotEmpty()) {
            font.color = Color(1f, 0.92f, 0.45f, 1f)
            font.draw(batch, page.speakerName, nameplateX + 6f, nameplateY + 11f)
        }

        // 본문 대사 텍스트 렌더링 (줄바꿈 지원)
        font.color = Color.WHITE
        val lines = dialogueManager.visibleText.split("\n")
        var currentY = textStartY
        for (line in lines) {
            font.draw(batch, line, textStartX, currentY)
            currentY -= 14f
        }

        // 페이지 완료 시 깜빡이는 '▼' 대기 커서 렌더링
        if (dialogueManager.isPageComplete && (blinkTimer % 0.8f) < 0.5f) {
            font.color = Color(1f, 0.85f, 0.3f, 1f)
            font.draw(batch, "▼", boxX + boxW - 14f, boxY + 14f)
        }

        batch.end()
    }

    private fun renderWindowFrame(speakerName: String) {
        // 메인 대화창 배경
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = Color(0.04f, 0.08f, 0.18f, 0.90f)
        shapeRenderer.rect(boxX, boxY, boxW, boxH)

        // 초상화 슬롯 배경
        shapeRenderer.color = Color(0.02f, 0.04f, 0.10f, 0.95f)
        shapeRenderer.rect(portraitX, portraitY, portraitSize, portraitSize)

        // 화자 명패 배경
        if (speakerName.isNotEmpty()) {
            val nameWidth = (speakerName.length * 12f + 14f).coerceAtLeast(48f)
            shapeRenderer.color = Color(0.06f, 0.10f, 0.22f, 0.95f)
            shapeRenderer.rect(nameplateX, nameplateY, nameWidth, nameplateH)
        }
        shapeRenderer.end()

        // 골드 테두리 라인
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        // 바깥 밝은 골드 프레임
        shapeRenderer.color = Color(0.85f, 0.72f, 0.30f, 1f)
        shapeRenderer.rect(boxX, boxY, boxW, boxH)
        shapeRenderer.rect(portraitX, portraitY, portraitSize, portraitSize)

        // 안쪽 어두운 골드 프레임
        shapeRenderer.color = Color(0.40f, 0.32f, 0.12f, 1f)
        shapeRenderer.rect(boxX + 1f, boxY + 1f, boxW - 2f, boxH - 2f)
        shapeRenderer.rect(portraitX + 1f, portraitY + 1f, portraitSize - 2f, portraitSize - 2f)

        // 화자 명패 골드 테두리
        if (speakerName.isNotEmpty()) {
            val nameWidth = (speakerName.length * 12f + 14f).coerceAtLeast(48f)
            shapeRenderer.color = Color(0.85f, 0.72f, 0.30f, 1f)
            shapeRenderer.rect(nameplateX, nameplateY, nameWidth, nameplateH)
        }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
        defaultAvatarTexture?.dispose()
    }
}
