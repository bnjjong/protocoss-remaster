package com.protocoss.dialogue

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.utils.Disposable

/**
 * 고전 1995년 감성의 한글 픽셀 비트맵 폰트를 생성하고 캐싱하는 폰트 관리자입니다.
 */
class FontManager : Disposable {

    private val fontCache = mutableMapOf<Int, BitmapFont>()
    private val generatorCache = mutableMapOf<Int, FreeTypeFontGenerator>()

    /**
     * 지정한 크기 [fontSize]의 레트로 한글 픽셀 폰트를 반환합니다.
     *
     * @param fontSize 폰트 크기 (픽셀 단위, 기본값 11px)
     * @param fontPath TTF 폰트 파일 에셋 경로
     * @return 렌더링에 사용할 [BitmapFont]
     */
    fun getFont(
        fontSize: Int = 11,
        fontPath: String = "assets/fonts/Galmuri11.ttf"
    ): BitmapFont {
        fontCache[fontSize]?.let { return it }

        val font = try {
            val fileHandle = Gdx.files.internal(fontPath)
            if (fileHandle.exists()) {
                val generator = FreeTypeFontGenerator(fileHandle)
                generatorCache[fontSize] = generator
                val parameter = FreeTypeFontParameter().apply {
                    size = fontSize
                    color = Color.WHITE
                    borderWidth = 1f
                    borderColor = Color(0f, 0f, 0f, 0.85f)
                    characters = buildKoreanCharacterSet()
                    incremental = true // 동적 글리프 래스터라이징 (generator 보존 필수)
                }
                generator.generateFont(parameter)
            } else {
                BitmapFont()
            }
        } catch (e: Throwable) {
            Gdx.app?.log("FontManager", "FreeType font generation failed, fallback to default: ${e.message}")
            BitmapFont()
        }

        fontCache[fontSize] = font
        return font
    }

    override fun dispose() {
        fontCache.values.forEach { it.dispose() }
        fontCache.clear()
        generatorCache.values.forEach { it.dispose() }
        generatorCache.clear()
    }

    private fun buildKoreanCharacterSet(): String {
        val sb = StringBuilder()
        sb.append(FreeTypeFontGenerator.DEFAULT_CHARS)
        sb.append("▼▶◀▲…·「」『』!?~@#$%^&*()-_=+[]{}|;:'\",.<>/?")
        return sb.toString()
    }
}
